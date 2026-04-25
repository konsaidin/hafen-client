package haven.gamepad;

import net.java.games.input.*;

import java.util.logging.Logger;

/**
 * Polls the first detected gamepad via jinput on a background thread.
 * Publishes an immutable GamepadState snapshot each tick.
 * Set java.library.path to include lib/jinput-natives/ before startup.
 */
public class GamepadManager {
    private static final Logger log = Logger.getLogger(GamepadManager.class.getName());
    private static final long POLL_INTERVAL_MS = 8; // ~120 Hz

    private volatile GamepadState state = GamepadState.EMPTY;
    private volatile boolean running = false;

    private final GamepadConfig cfg;
    private Thread pollThread;
    private Controller activeController;

    // Previous button states for edge detection (press/release events)
    private volatile GamepadState prevState = GamepadState.EMPTY;

    public GamepadManager(GamepadConfig cfg) {
	this.cfg = cfg;
    }

    public void start() {
	running = true;
	pollThread = new Thread(this::pollLoop, "GamepadPoller");
	pollThread.setDaemon(true);
	pollThread.start();
    }

    public void stop() {
	running = false;
	if(pollThread != null)
	    pollThread.interrupt();
    }

    /** Returns the latest snapshot. Never null. */
    public GamepadState getState() {
	return state;
    }

    /** True when a gamepad is currently detected and sending input. */
    public boolean isConnected() {
	return state != GamepadState.EMPTY;
    }

    /** Returns the state from the previous tick (for edge detection). */
    public GamepadState getPrevState() {
	return prevState;
    }

    private void pollLoop() {
	while(running) {
	    try {
		if(activeController == null || !activeController.poll())
		    activeController = findGamepad();

		if(activeController != null && activeController.poll()) {
		    prevState = state;
		    state = readState(activeController);
		} else {
		    prevState = state;
		    state = GamepadState.EMPTY;
		}
		Thread.sleep(POLL_INTERVAL_MS);
	    } catch(InterruptedException e) {
		Thread.currentThread().interrupt();
		break;
	    } catch(Exception e) {
		log.warning("Gamepad poll error: " + e.getMessage());
		activeController = null;
		try { Thread.sleep(1000); } catch(InterruptedException ie) { break; }
	    } catch(Error e) {
		// jinput native init failure (UnsatisfiedLinkError, NoClassDefFoundError, etc.)
		// — gamepad unavailable, abort poll loop rather than spamming the log
		log.warning("Gamepad unavailable: " + e);
		state = GamepadState.EMPTY;
		return;
	    }
	}
    }

    private Controller findGamepad() {
	ControllerEnvironment env = ControllerEnvironment.getDefaultEnvironment();
	for(Controller c : env.getControllers()) {
	    Controller.Type t = c.getType();
	    if(t == Controller.Type.GAMEPAD || t == Controller.Type.STICK) {
		log.info("Gamepad found: " + c.getName());
		logComponents(c);
		return c;
	    }
	}
	return null;
    }

    private void logComponents(Controller c) {
	StringBuilder sb = new StringBuilder("Gamepad components (stderr):\n");
	int btnIdx = 0;
	for(Component comp : c.getComponents()) {
	    Component.Identifier id = comp.getIdentifier();
	    String idStr = (id instanceof Component.Identifier.Button)
		? "Button[" + (btnIdx++) + "] id=" + id
		: "Axis id=" + id;
	    sb.append("  ").append(idStr).append(" name=").append(comp.getName()).append("\n");
	}
	// Use stderr so it's always visible regardless of logging config
	System.err.print(sb);
    }

    private GamepadState readState(Controller ctrl) {
	Component[] comps = ctrl.getComponents();

	float lx = 0, ly = 0, rx = 0, ry = 0, l2 = 0, r2 = 0;
	boolean btnA = false, btnB = false, btnX = false, btnY = false;
	boolean l1 = false, r1 = false, ls = false, rs = false;
	boolean dU = false, dD = false, dL = false, dR = false;
	boolean start = false, select = false;

	// Scan buttons by sequential position — works regardless of which Button._N
	// identifier jinput assigns to each physical button on this controller.
	int btnIdx = 0;

	for(Component c : comps) {
	    Component.Identifier id = c.getIdentifier();
	    float v = c.getPollData();

	    if(id == Component.Identifier.Axis.X)        lx = applyDeadZone(v, cfg.moveDeadZone);
	    else if(id == Component.Identifier.Axis.Y)   ly = applyDeadZone(v, cfg.moveDeadZone);
	    else if(id == Component.Identifier.Axis.RX)  rx = applyDeadZone(v, cfg.mouseDeadZone);
	    else if(id == Component.Identifier.Axis.RY)  ry = applyDeadZone(v, cfg.mouseDeadZone);
	    else if(id == Component.Identifier.Axis.Z)   l2 = (v + 1f) / 2f; // -1..1 → 0..1
	    else if(id == Component.Identifier.Axis.RZ)  r2 = (v + 1f) / 2f;
	    else if(id instanceof Component.Identifier.Button) {
		boolean pressed = v > 0.5f;
		switch(btnIdx) {
		    // DualSense on Linux (sequential evdev order):
		    // 0=Cross(A), 1=Circle(B), 2=Square(X), 3=Triangle(Y),
		    // 4=L1, 5=R1, 6=L2-digital(skip), 7=R2-digital(skip),
		    // 8=Create/Share, 9=Options, 10=L3, 11=R3
		    case  0: btnA   = pressed; break;
		    case  1: btnB   = pressed; break;
		    case  2: btnX   = pressed; break;
		    case  3: btnY   = pressed; break;
		    case  4: l1     = pressed; break;
		    case  5: r1     = pressed; break;
		    // 6=L2 digital, 7=R2 digital — read via Axis.Z/RZ, skip here
		    case  8: select = pressed; break;
		    case  9: start  = pressed; break;
		    case 10: ls     = pressed; break;
		    case 11: rs     = pressed; break;
		}
		btnIdx++;
	    } else if(id == Component.Identifier.Axis.POV) {
		// Standard POV hat: 0=centered, 0.125=N, 0.25=NE, 0.375=E, 0.5=SE,
		//                   0.625=S, 0.75=SW, 0.875=W, 1.0=NW
		dU = (v == 0.125f || v == 1.0f  || v == 0.25f);
		dR = (v == 0.25f  || v == 0.375f || v == 0.5f);
		dD = (v == 0.5f   || v == 0.625f || v == 0.75f);
		dL = (v == 0.75f  || v == 0.875f || v == 1.0f);
	    }
	}

	return new GamepadState(lx, ly, rx, ry, l2, r2,
	    btnA, btnB, btnX, btnY,
	    l1, r1, ls, rs,
	    dU, dD, dL, dR,
	    start, select,
	    cfg.triggerThreshold);
    }

    /** Applies symmetric dead zone and rescales the remaining range to [-1, 1]. */
    private static float applyDeadZone(float v, float dz) {
	if(Math.abs(v) < dz)
	    return 0f;
	float sign = v > 0 ? 1f : -1f;
	return sign * ((Math.abs(v) - dz) / (1f - dz));
    }

    /** Returns true if the button just became pressed this tick. */
    public boolean justPressed(boolean cur, boolean prev) {
	return cur && !prev;
    }

    /** Returns true if the button just became released this tick. */
    public boolean justReleased(boolean cur, boolean prev) {
	return !cur && prev;
    }
}
