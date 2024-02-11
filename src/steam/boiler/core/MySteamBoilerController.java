package steam.boiler.core;

import java.util.LinkedList;
import java.util.Queue;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.SteamBoilerCharacteristics;

/**
 * Controller for specific steam boiler.
 *
 * @author joshkeegan
 *
 */
public class MySteamBoilerController implements SteamBoilerController {
  /**
   * Captures the various modes in which the controller can operate.
   *
   * @author David J. Pearce
   *
   */
  private enum State {
    /**
     * waiting.
     */
    WAITING,
    /**
     * ready.
     */
    READY,
    /**
     * normal.
     */
    NORMAL,
    /**
     * degraded.
     */
    DEGRADED,
    /**
     * rescue.
     */
    RESCUE,
    /**
     * emergency stop.
     */
    EMERGENCY_STOP;

    @Override
    public @NonNull String toString() {
      String s = super.toString();
      if (s != null) {
        return s;
      }
      throw new RuntimeException();
    }
  }

  /**
   * Records the configuration characteristics for the given boiler problem.
   */
  private final SteamBoilerCharacteristics configuration;

  /**
   * Identifies the current mode in which the controller is operating.
   */
  private State mode = State.WAITING;

  /**
   * Expected pump states on the next cycle.
   */
  private final boolean[] expectedPumpStates;

  /**
   * Expected pump controller states on the next cycle.
   */
  private final boolean[] expectedPumpControlStates;

  /**
   * Minimum expected water level on the next cycle.
   */
  private int minimumExpectedLevel = 0;

  /**
   * Maximum expected water level on the next cycle.
   */
  private int maximnumExpectedLevel;

  /**
   * Number of times received stop in a row. Once received 3 times in a row the
   * program enters emergency stop
   */
  private int stopCount = 0;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    this.expectedPumpStates = new boolean[configuration.getNumberOfPumps()];
    this.expectedPumpControlStates = new boolean[configuration.getNumberOfPumps()];
    this.maximnumExpectedLevel = (int) configuration.getCapacity();
  }

  /**
   * This message is displayed in the simulation window, and enables a limited
   * form of debug output. The content of the message has no material effect on
   * the system, and can be whatever is desired. In principle, however, it should
   * display a useful message indicating the current state of the controller.
   *
   * @return mode
   */

  @Override
  public String getStatusMessage() {
    return this.mode.toString();

  }

  /**
   * Process a clock signal which occurs every 5 seconds. This requires reading
   * the set of incoming messages from the physical units and producing a set of
   * output messages which are sent back to them.
   *
   * @param incoming The set of incoming messages from the physical units.
   * @param outgoing Messages generated during the execution of this method should
   *                 be written here.
   */
  @Override
  public void clock(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
    // Extract expected messages
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b,
        incoming);

    Message stopMessage = extractOnlyMatch(MessageKind.STOP, incoming);
    if (stopMessage != null) {
      this.stopCount++;
      if (this.stopCount >= 3) {
        this.mode = State.EMERGENCY_STOP;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      }
    } else {
      this.stopCount = 0;
    }

    if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages,
        pumpControlStateMessages)) {
      // Level and steam messages required, so emergency stop.
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
    }
    // Initialise system
    if (levelMessage != null && this.mode == State.WAITING) {
      boolean physicalUnitsReady = extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING,
          incoming) != null;
      init(levelMessage.getDoubleParameter(), physicalUnitsReady, outgoing);
      if (steamMessage == null || steamMessage.getDoubleParameter() != 0
          || (levelMessage.getDoubleParameter() < 0
              || levelMessage.getDoubleParameter() > this.configuration.getCapacity())) {
        this.mode = State.EMERGENCY_STOP;
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      }
    } else if (this.mode == State.READY) { // Put System in a running state
      this.mode = State.NORMAL;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
    } else if (levelMessage != null && steamMessage != null && (this.mode == State.NORMAL
        || this.mode == State.DEGRADED || this.mode == State.RESCUE)) {
      checkDegraded(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages,
          incoming, outgoing);
      checkRescue(levelMessage, incoming, outgoing);
      checkWaterLevel(levelMessage, outgoing);
      doNormalOperation((int) levelMessage.getDoubleParameter(), outgoing);
    }
  }

  /**
   * Check whether there was a transmission failure. This is indicated in several
   * ways. Firstly, when one of the required messages is missing. Secondly, when
   * the values returned in the messages are nonsensical.
   *
   * @param levelMessage      Extracted LEVEL_v message.
   * @param steamMessage      Extracted STEAM_v message.
   * @param pumpStates        Extracted PUMP_STATE_n_b messages.
   * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
   * @return Has been failure
   */
  private boolean transmissionFailure(@Nullable Message levelMessage,
      @Nullable Message steamMessage, Message[] pumpStates, Message[] pumpControlStates) {
    // Check level readings
    if (levelMessage == null) {
      // Nonsense or missing level reading
      return true;
    } else if (steamMessage == null) {
      // Nonsense or missing steam reading
      return true;
    } else if (pumpStates.length != this.configuration.getNumberOfPumps()) {
      // Nonsense pump state readings
      return true;
    } else if (pumpControlStates.length != this.configuration.getNumberOfPumps()) {
      // Nonsense pump control state readings
      return true;
    }
    // Done
    return false;
  }

  /**
   * Initialise the system by raising or lowering the water level. Ideal water
   * level is the average between water normal levels.
   *
   * @param level              Current water level.
   * @param physicalUnitsReady The status of the Physical units. True is ready.
   * @param outgoing           Messages generated during the execution of this
   *                           method should be written here.
   */
  private void init(double level, boolean physicalUnitsReady, Mailbox outgoing) {
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
    if (physicalUnitsReady) {
      if (level > this.configuration.getMaximalNormalLevel()) {
        outgoing.send(new Message(MessageKind.VALVE));
      } else if (level < this.configuration.getMinimalNormalLevel()) {
        for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
          outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
        }
      } else {
        outgoing.send(new Message(MessageKind.PROGRAM_READY));
        this.mode = State.READY;
        for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
          outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
        }
      }
    }
  }

  /**
   * Handle a functioning system.
   *
   * @param level    Water level
   * @param outgoing Messages generated during the execution of this method should
   *                 be written here.
   * 
   */
  private void doNormalOperation(int level, Mailbox outgoing) {
    int[] pumpTotals = new int[this.configuration.getNumberOfPumps()];

    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      pumpTotals[i] = (int) this.configuration.getPumpCapacity(i);
    }

    int avg = (int) (this.configuration.getMaximalNormalLevel()
        + this.configuration.getMinimalNormalLevel()) / 2;

    int amountPumped = turnPumpsOn(pumpTotals, avg - level, outgoing);
    int expectedLevelNoSteam = level + amountPumped * 5;
    this.maximnumExpectedLevel = expectedLevelNoSteam;
    this.minimumExpectedLevel = expectedLevelNoSteam
        - (int) this.configuration.getMaximualSteamRate() * 5;
  }

  /**
   * Turn on pumps required to meet a given pumpage.
   *
   * @param pumps           All pumps strength
   * @param pumpageRequired Amount needed to pump
   * @param outgoing        Messages generated during the execution of this method
   *                        should be written here.
   * @return the amount actually pumped
   */
  private int turnPumpsOn(int[] pumps, double pumpageRequired, Mailbox outgoing) {

    // turn off pumps to prepare for turning them on
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
      this.expectedPumpStates[i] = false;
      this.expectedPumpControlStates[i] = false;
    }

    String[] combinations = MySteamBoilerController
        .getCombinations((int) Math.pow(2, pumps.length));
    double min = Double.MAX_VALUE;
    String minc = new String();
    int pumpage = 0;
    for (int i = 0; i < combinations.length; i++) {
      int sum = 0;
      String combination = combinations[i];
      for (int j = 0; j < combination.length(); j++) {
        if (combination.charAt(j) == '1') {
          sum += pumps[j];
        }
      }
      if (Math.abs(pumpageRequired - sum) < min) {
        minc = combination;
        min = Math.abs(pumpageRequired - sum);
        pumpage = sum;
      }
    }

    for (int j = 0; j < minc.length(); j++) {
      if (minc.charAt(j) == '1') {
        outgoing.send(new Message(Mailbox.MessageKind.OPEN_PUMP_n, j));
        this.expectedPumpStates[j] = true;
        this.expectedPumpControlStates[j] = true;
      }
    }
    return pumpage;
  }

  /**
   * Adapted from
   * https://www.geeksforgeeks.org/interesting-method-generate-binary-numbers-1-n/
   * Generates binary numbers from 1 - n
   *
   * @param n - max binary number
   * @return generated combinations
   */
  private static String[] getCombinations(int n) {
    assert n > 0;

    String[] output = new String[n + 1];
    output[0] = "0"; //$NON-NLS-1$
    // Create an empty queue of strings
    Queue<@Nullable String> q = new LinkedList<>();

    // Enqueue the first binary number
    q.add("1"); //$NON-NLS-1$

    // This loops is like BFS of a tree with 1 as root
    // 0 as left child and 1 as right child and so on
    for (int i = n; i > 0; i--) {
      // print the front of queue
      String s1 = ""; //$NON-NLS-1$
      s1 = q.peek();
      q.remove();
      output[i] = s1;

      // Store s1 before changing it
      String s2 = s1;

      // Append "0" to s1 and enqueue it
      q.add(s1 + "0"); //$NON-NLS-1$

      // Append "1" to s2 and enqueue it. Note that s2
      // contains the previous front
      q.add(s2 + "1"); //$NON-NLS-1$
    }
    return output;
  }

  /**
   * Checks if the components are degraded. Puts appropriate messages in mailbox.
   *
   * @param levelMessage             Contains the water level
   * @param steamMessage             Contains the steam level
   * @param pumpStateMessages        Contains the state of the pumps
   * @param pumpControlStateMessages Contains the sate of the pump controllers
   * @param incoming                 The set of incoming messages from the
   *                                 physical units.
   * @param outgoing                 Messages generated during the execution of
   *                                 this method should be written here.
   */
  private void checkDegraded(Message levelMessage, Message steamMessage,
      Message[] pumpStateMessages, Message[] pumpControlStateMessages, Mailbox incoming,
      Mailbox outgoing) {
    this.mode = State.NORMAL;

    checkSteamDegraded(steamMessage, incoming, outgoing);

    boolean[] repairedControl = new boolean[this.configuration.getNumberOfPumps()];
    Message[] repairedControlMatches = extractAllMatches(MessageKind.PUMP_CONTROL_REPAIRED_n,
        incoming);
    for (int i = 0; i < repairedControlMatches.length; i++) {
      repairedControl[repairedControlMatches[i].getIntegerParameter()] = true;
    }

    boolean[] repairedPump = new boolean[this.configuration.getNumberOfPumps()];
    Message[] repairedPumpMatches = extractAllMatches(MessageKind.PUMP_REPAIRED_n, incoming);
    for (int i = 0; i < repairedPumpMatches.length; i++) {
      repairedPump[repairedPumpMatches[i].getIntegerParameter()] = true;
    }

    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (this.expectedPumpControlStates[i] != pumpControlStateMessages[i].getBooleanParameter()
          && pumpControlStateMessages[i].getBooleanParameter() != pumpStateMessages[i]
              .getBooleanParameter()) {
        this.mode = State.DEGRADED;
        outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
      } else if (this.expectedPumpControlStates[i] == pumpControlStateMessages[i]
          .getBooleanParameter() && repairedControl[i]
          && pumpControlStateMessages[i].getBooleanParameter() == pumpStateMessages[i]
              .getBooleanParameter()) {
        outgoing.send(new Message(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n, i));
      }
      if (this.expectedPumpStates[i] != pumpStateMessages[i].getBooleanParameter()) {
        this.mode = State.DEGRADED;
        outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
      } else if (this.expectedPumpStates[i] == pumpStateMessages[i].getBooleanParameter()
          && repairedPump[i]) {
        outgoing.send(new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n, i));
      }
    }
    if (this.mode == State.DEGRADED) {
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
    } else {
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
    }
  }

  /**
   * Check if the steam level is degraded.
   *
   * @param steamMessage Steam level reading received from the physical units.
   * @param incoming     The set of incoming messages from the physical units.
   * @param outgoing     Messages generated during the execution of this method
   *                     should be written here.
   */

  private void checkSteamDegraded(Message steamMessage, Mailbox incoming, Mailbox outgoing) {
    if (steamMessage.getDoubleParameter() < 0
        || steamMessage.getDoubleParameter() > this.configuration.getMaximualSteamRate()) {
      this.mode = State.DEGRADED;
      outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
    } else if (steamMessage.getDoubleParameter() > 0
        || steamMessage.getDoubleParameter() < this.configuration.getMaximualSteamRate()
            && extractOnlyMatch(MessageKind.STEAM_REPAIRED, incoming) != null) {
      outgoing.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
    }
  }

  /**
   * Checks if the the level sensor doesn't work as intended. Puts appropriate
   * messages in mailbox.
   *
   * @param levelMessage Contains the water level
   * @param incoming     The set of incoming messages from the physical units.
   * @param outgoing     Messages generated during the execution of this method
   *                     should be written here.
   */

  private void checkRescue(Message levelMessage, Mailbox incoming, Mailbox outgoing) {
    if (levelMessage.getDoubleParameter() > this.maximnumExpectedLevel
        || levelMessage.getDoubleParameter() < this.minimumExpectedLevel) {
      this.mode = State.RESCUE;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
    } else if (levelMessage.getDoubleParameter() < this.maximnumExpectedLevel
        || levelMessage.getDoubleParameter() > this.minimumExpectedLevel
            && extractOnlyMatch(MessageKind.LEVEL_REPAIRED, incoming) != null) {
      outgoing.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
    }

  }

  /**
   * Check water level is in valid range Otherwise enter emergency stop.
   *
   * @param levelMessage Contains the water level
   * @param outgoing     Messages generated during the execution of this method
   *                     should be written here.
   */
  private void checkWaterLevel(Message levelMessage, Mailbox outgoing) {
    if (levelMessage.getDoubleParameter() > this.configuration.getMaximalLimitLevel()
        || levelMessage.getDoubleParameter() < this.configuration.getMinimalLimitLevel()) {
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
    }
  }

  /**
   * Find and extract a message of a given kind in a mailbox. This must the only
   * match in the mailbox, else <code>null</code> is returned.
   *
   * @param kind     The kind of message to look for.
   * @param incoming The mailbox to search through.
   * @return The matching message, or <code>null</code> if there was not exactly
   *         one match.
   */
  private static @Nullable Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
    Message match = null;
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        if (match == null) {
          match = ith;
        } else {
          // This indicates that we matched more than one message of the given kind.
          return null;
        }
      }
    }
    return match;
  }

  /**
   * Find and extract all messages of a given kind.
   *
   * @param kind     The kind of message to look for.
   * @param incoming The mailbox to search through.
   * @return The array of matches, which can empty if there were none.
   */
  private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
    int count = 0;
    // Count the number of matches
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        count = count + 1;
      }
    }
    // Now, construct resulting array
    Message[] matches = new Message[count];
    int index = 0;
    for (int i = 0; i != incoming.size(); ++i) {
      Message ith = incoming.read(i);
      if (ith.getKind() == kind) {
        matches[index++] = ith;
      }
    }
    return matches;
  }
}
