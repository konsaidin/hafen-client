package haven.gamepad;

import haven.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the best interactive Gob in a cone in front of the player.
 *
 * Normal mode priority (lower number = higher priority):
 *   1 – doors / gates
 *   2 – loot / items on ground
 *   3 – bags near a vehicle (matryoshka)
 *   4 – crafting stations / workbenches
 *   5 – containers (chests, baskets)
 *   6 – animals / NPCs
 *   7 – resources (trees, rocks)
 *
 * Combat mode priority:
 *   1 – locked target (last combat target)
 *   2 – nearest hostile animal
 *   3 – nearest animal / NPC
 *   4 – player character (PvP)
 */
public class SmartTarget {
    private static final double TILE = 11.0;

    private final GamepadConfig cfg;
    /** Last direction the player was moving, in world radians. Used as gaze vector. */
    private float lastMoveAngle = 0f;
    private long lastMoveTime = 0;
    /** Id of the Gob chosen as combat lock target. */
    private long combatLockId = -1;

    public static class Entry {
	public final Gob gob;
	public final int priority;
	public final double dist;
	public final String resName;

	public Entry(Gob gob, int priority, double dist, String resName) {
	    this.gob = gob;
	    this.priority = priority;
	    this.dist = dist;
	    this.resName = resName;
	}
    }

    public SmartTarget(GamepadConfig cfg) {
	this.cfg = cfg;
    }

    /** Update the stored gaze direction from the latest movement vector. */
    public void updateGaze(float worldDx, float worldDy) {
	if(Math.abs(worldDx) > 0.01f || Math.abs(worldDy) > 0.01f) {
	    lastMoveAngle = (float) Math.atan2(worldDy, worldDx);
	    lastMoveTime = System.currentTimeMillis();
	}
    }

    /**
     * Set or clear the combat lock target (used as priority-1 in combat mode).
     */
    public void setCombatLock(long gobId) {
	this.combatLockId = gobId;
    }

    /**
     * Returns a sorted list of Gobs in the targeting cone.
     * List is sorted by (priority, distance). Empty if nothing found.
     *
     * @param map    active MapView
     * @param player player Gob
     * @param maxResults cap on returned entries (use Integer.MAX_VALUE for all)
     */
    public List<Entry> scan(MapView map, Gob player, int maxResults) {
	Coord2d playerPos = player.rc;
	float gazeAngle = gazeAngle(map);
	double coneHalfRad = Math.toRadians(cfg.targetConeHalfDeg);
	double maxDist = cfg.targetConeDepthTiles * TILE;

	OCache oc = player.glob.oc;
	List<Entry> result = new ArrayList<>();

	// Collect vehicle positions to detect bags near them
	List<Coord2d> vehiclePositions = new ArrayList<>();
	for(Gob g : oc) {
	    String rn = resName(g);
	    if(rn != null && rn.contains("vehicle"))
		vehiclePositions.add(g.rc);
	}

	for(Gob g : oc) {
	    if(g == player) continue;
	    double dx = g.rc.x - playerPos.x;
	    double dy = g.rc.y - playerPos.y;
	    double dist = Math.sqrt(dx * dx + dy * dy);
	    if(dist > maxDist || dist < 0.5)
		continue;

	    // Cone check
	    float gobAngle = (float) Math.atan2(dy, dx);
	    float angleDiff = angleDiff(gobAngle, gazeAngle);
	    if(Math.abs(angleDiff) > coneHalfRad)
		continue;

	    String rn = resName(g);
	    int prio = priority(rn, g.rc, vehiclePositions, cfg.combatMode, combatLockId, g.id);
	    if(prio < 0) continue; // ignored

	    result.add(new Entry(g, prio, dist, rn != null ? rn : ""));

	    if(result.size() >= maxResults * 4) // over-collect then trim
		break;
	}

	result.sort((a, b) -> {
	    if(a.priority != b.priority) return Integer.compare(a.priority, b.priority);
	    return Double.compare(a.dist, b.dist);
	});

	return result.subList(0, Math.min(result.size(), maxResults));
    }

    /** Returns the single best target, or null. */
    public Entry best(MapView map, Gob player) {
	List<Entry> hits = scan(map, player, 1);
	return hits.isEmpty() ? null : hits.get(0);
    }

    // -------------------------------------------------------------------------

    private float gazeAngle(MapView map) {
	long ago = System.currentTimeMillis() - lastMoveTime;
	if(ago < 2000)
	    return lastMoveAngle;
	// No recent movement: use camera forward direction (opposite of camera angle)
	float camAngle = map.camera.angle();
	return camAngle + (float) Math.PI; // camera looks from behind player
    }

    /** Signed angular difference, wrapped to [-PI, PI]. */
    private static float angleDiff(float a, float b) {
	float d = a - b;
	while(d > Math.PI)  d -= (float)(2 * Math.PI);
	while(d < -Math.PI) d += (float)(2 * Math.PI);
	return d;
    }

    /** Safely resolves the resource name of a Gob, or null. */
    public static String resName(Gob g) {
	try {
	    Drawable dr = g.getattr(Drawable.class);
	    if(dr instanceof ResDrawable) {
		Resource res = ((ResDrawable) dr).rres;
		return (res != null) ? res.name : null;
	    }
	} catch(Exception e) {
	    // Resource not yet loaded
	}
	return null;
    }

    /**
     * Maps a resource name to a targeting priority.
     * Returns -1 if the object should be ignored entirely.
     */
    private static int priority(String rn, Coord2d pos, List<Coord2d> vehicles,
				boolean combat, long lockId, long gobId) {
	if(combat) {
	    if(gobId == lockId)  return 1;
	    if(rn == null)       return -1;
	    if(isAnimal(rn))     return 2;
	    if(isPlayer(rn))     return 4;
	    return -1; // non-combat objects ignored in combat mode
	}

	if(rn == null) return -1;

	if(isDoor(rn))      return 1;
	if(isLoot(rn))      return 2;
	if(isBagNearVehicle(pos, vehicles)) return 3;
	if(isStation(rn))   return 4;
	if(isContainer(rn)) return 5;
	if(isAnimal(rn))    return 6;
	if(isResource(rn))  return 7;

	return -1;
    }

    private static boolean isDoor(String rn) {
	return rn.contains("/gate") || rn.contains("/door");
    }

    private static boolean isLoot(String rn) {
	return rn.startsWith("gfx/terobjs/items/") || rn.startsWith("gfx/terobjs/lnd/");
    }

    /** A bag-like object sitting close to a vehicle gob. */
    private static boolean isBagNearVehicle(Coord2d pos, List<Coord2d> vehicles) {
	for(Coord2d v : vehicles) {
	    double dx = pos.x - v.x, dy = pos.y - v.y;
	    if(dx * dx + dy * dy < 3 * TILE * 3 * TILE)
		return true;
	}
	return false;
    }

    private static boolean isStation(String rn) {
	// Crafting benches, forges, kilns, looms, etc.
	return rn.startsWith("gfx/terobjs/") && !isContainer(rn) && !isDoor(rn) && !isLoot(rn);
    }

    private static boolean isContainer(String rn) {
	return rn.contains("/chest") || rn.contains("/basket") || rn.contains("/crate")
	    || rn.contains("/barrel") || rn.contains("/coffer");
    }

    private static boolean isAnimal(String rn) {
	return rn.startsWith("gfx/kritter/") || rn.startsWith("gfx/terobjs/animals/");
    }

    private static boolean isPlayer(String rn) {
	return rn.startsWith("gfx/borka/");
    }

    private static boolean isResource(String rn) {
	return rn.startsWith("gfx/trees/") || rn.startsWith("gfx/stones/")
	    || rn.startsWith("gfx/bushes/") || rn.startsWith("gfx/herbs/");
    }
}
