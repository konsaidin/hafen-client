package haven.gamepad;

import haven.*;

import static haven.OCache.posres;

/**
 * Converts left-stick input into repeated map clicks for direct movement.
 *
 * Click distance: ~2 tiles ahead of the player along the stick direction,
 * rotated by the current camera angle so "up on stick = forward on screen".
 *
 * Stopping: when stick is released we simply stop sending clicks; the server
 * walks the character to the last issued target (≤ 2 tiles away) and stops —
 * no overshoot visible to the player.
 */
public class DirectMovement {
    // 1 tile = 11 world units (see OCache.posres)
    private static final double TILE = 11.0;

    private final GamepadConfig cfg;

    private long lastClickAt = 0;

    public DirectMovement(GamepadConfig cfg) {
	this.cfg = cfg;
    }

    /**
     * Call once per UI tick. Sends a movement click to {@code map} when the
     * stick is active and the click interval has elapsed.
     *
     * @param map   active MapView
     * @param state current gamepad snapshot
     */
    public void tick(MapView map, GamepadState state) {
	if(!state.lsActive(cfg.moveDeadZone))
	    return;

	long now = System.currentTimeMillis();
	if(now - lastClickAt < cfg.clickIntervalMs)
	    return;

	Coord3f playerWorld;
	try {
	    playerWorld = map.getcc();
	} catch(Loading e) {
	    return;
	}
	if(playerWorld == null)
	    return;

	// Camera yaw: angle() returns the current horizontal rotation of FollowCam.
	// We rotate the stick vector by the camera angle so stick-up always means
	// "away from camera" (forward in camera space).
	float camAngle = map.camera.angle();
	float sinA = (float) Math.sin(camAngle);
	float cosA = (float) Math.cos(camAngle);

	// Stick axes: lx=right, ly=down (standard joystick convention).
	// Camera-space forward = -cosA, -sinA (away from camera).
	// Camera-space right   = sinA, -cosA.
	float stickX = state.lx;
	float stickY = state.ly;

	// Rotate stick into world XY.
	// Camera is positioned at angle camAngle from the player (in GL space, Y inverted).
	// Forward in world space = (-cosA,  sinA), right = (sinA, cosA).
	// Stick-up (ly=-1) → forward; stick-right (lx=+1) → right.
	float worldDx = stickX * sinA - stickY * cosA;
	float worldDy = stickX * cosA + stickY * sinA;

	// Normalise so diagonal doesn't move faster
	float mag = (float) Math.sqrt(worldDx * worldDx + worldDy * worldDy);
	if(mag < 0.001f)
	    return;
	worldDx /= mag;
	worldDy /= mag;

	double dist = cfg.clickDistTiles * TILE;
	double targetX = playerWorld.x + worldDx * dist;
	double targetY = playerWorld.y + worldDy * dist;

	// World coordinate — same space as gob.rc, no Y inversion needed
	Coord2d worldTarget = Coord2d.of(targetX, targetY);

	// Screen coordinate for click-flash visual
	Coord3f screenPos = map.screenxf(worldTarget);
	Coord screenCoord = (screenPos != null)
	    ? new Coord((int) screenPos.x, (int) screenPos.y)
	    : new Coord(map.sz.x / 2, map.sz.y / 2);

	// Send movement click: button 1 (LMB), no mods, no gob target
	map.wdgmsg("click", screenCoord, worldTarget.floor(posres), 1, 0);
	lastClickAt = now;
    }
}
