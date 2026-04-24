package haven.gamepad;

/** Immutable snapshot of gamepad state for a single tick. Thread-safe by immutability. */
public final class GamepadState {
    public static final GamepadState EMPTY = new GamepadState();

    // Left stick: -1..1
    public final float lx, ly;
    // Right stick: -1..1
    public final float rx, ry;
    // Triggers: 0..1
    public final float l2, r2;

    // Face buttons
    public final boolean btnA, btnB, btnX, btnY;
    // Shoulders
    public final boolean l1, r1;
    // Stick clicks
    public final boolean ls, rs;
    // D-pad
    public final boolean dpadUp, dpadDown, dpadLeft, dpadRight;
    // Menu buttons
    public final boolean start, select;

    // Computed convenience
    public final boolean l2Held;   // trigger past threshold
    public final boolean r2Held;

    private GamepadState() {
	lx = ly = rx = ry = l2 = r2 = 0f;
	btnA = btnB = btnX = btnY = false;
	l1 = r1 = ls = rs = false;
	dpadUp = dpadDown = dpadLeft = dpadRight = false;
	start = select = false;
	l2Held = r2Held = false;
    }

    public GamepadState(float lx, float ly, float rx, float ry,
			float l2, float r2,
			boolean btnA, boolean btnB, boolean btnX, boolean btnY,
			boolean l1, boolean r1, boolean ls, boolean rs,
			boolean dpadUp, boolean dpadDown, boolean dpadLeft, boolean dpadRight,
			boolean start, boolean select,
			float triggerThreshold) {
	this.lx = lx; this.ly = ly;
	this.rx = rx; this.ry = ry;
	this.l2 = l2; this.r2 = r2;
	this.btnA = btnA; this.btnB = btnB; this.btnX = btnX; this.btnY = btnY;
	this.l1 = l1; this.r1 = r1;
	this.ls = ls; this.rs = rs;
	this.dpadUp = dpadUp; this.dpadDown = dpadDown;
	this.dpadLeft = dpadLeft; this.dpadRight = dpadRight;
	this.start = start; this.select = select;
	this.l2Held = l2 > triggerThreshold;
	this.r2Held = r2 > triggerThreshold;
    }

    /** True if left stick magnitude exceeds the given dead zone. */
    public boolean lsActive(float deadZone) {
	return (lx * lx + ly * ly) > (deadZone * deadZone);
    }

    /** True if right stick magnitude exceeds the given dead zone. */
    public boolean rsActive(float deadZone) {
	return (rx * rx + ry * ry) > (deadZone * deadZone);
    }

    /** Left stick angle in radians from positive-x axis. */
    public float lsAngle() {
	return (float) Math.atan2(ly, lx);
    }

    /** Right stick angle in radians from positive-x axis. */
    public float rsAngle() {
	return (float) Math.atan2(ry, rx);
    }

    /** Left stick magnitude, clamped to [0, 1]. */
    public float lsMag() {
	return Math.min(1f, (float) Math.sqrt(lx * lx + ly * ly));
    }
}
