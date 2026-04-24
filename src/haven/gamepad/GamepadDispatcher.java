package haven.gamepad;

import haven.*;

import java.util.List;

import static haven.OCache.posres;

/**
 * Central gamepad dispatcher. Instantiated once by GameUI and ticked every
 * frame via GameUI.tick(). All gamepad logic lives here; GameUI stays clean.
 *
 * Call order each tick:
 *   1. Read GamepadState snapshot
 *   2. Dispatch: left stick → DirectMovement
 *                RS (no L2) → mouse emulation
 *                RS (L2)    → camera rotation
 *                RS press   → camera reset
 *                ABXY       → hotbar 1-4 / 5-8 (L2 modifier)
 *                L1         → toggle combat mode
 *                R1 tap     → smart target right-click
 *                R1 hold    → RadialPicker
 */
public class GamepadDispatcher {
    private static final float TWO_PI = (float)(2 * Math.PI);

    public final GamepadConfig  cfg;
    public final GamepadManager manager;

    private final DirectMovement movement;
    private final SmartTarget    target;

    private final GameUI gui;

    // R1 hold tracking
    private long   r1PressTime = 0;
    private boolean r1WasHeld  = false;
    private boolean radialOpen = false;
    private RadialPicker currentPicker = null;

    // L1 edge detection
    private boolean prevL1 = false;

    // RS click edge detection (camera reset)
    private boolean prevRs = false;

    public GamepadDispatcher(GameUI gui, GamepadConfig cfg) {
	this.gui = gui;
	this.cfg = cfg;
	this.manager = new GamepadManager(cfg);
	this.movement = new DirectMovement(cfg);
	this.target   = new SmartTarget(cfg);
	manager.start();
    }

    public void dispose() {
	manager.stop();
    }

    /** Called from GameUI.tick(). */
    public void tick(double dt) {
	MapView map = gui.map;
	if(map == null) return;

	GamepadState cur  = manager.getState();
	GamepadState prev = manager.getPrevState();

	// --- Left stick: direct movement ---
	if(cur.lsActive(cfg.moveDeadZone)) {
	    movement.tick(map, cur);
	    // Track gaze direction for smart target — same rotation math as DirectMovement
	    float camA = map.camera.angle();
	    float sinA = (float) Math.sin(camA), cosA = (float) Math.cos(camA);
	    float wdx = cur.lx * sinA - cur.ly * cosA;
	    float wdy = cur.lx * cosA + cur.ly * sinA;
	    target.updateGaze(wdx, wdy);
	}

	// --- Right stick: camera rotation (L2 held) or mouse emulation ---
	if(cur.l2Held) {
	    if(cur.rsActive(cfg.mouseDeadZone)) {
		// stick-right rotates; stick-up tilts more overhead (negative dElev)
		map.camera.gpRotate(
		    cur.rx * cfg.camRotSensX,
		    -cur.ry * cfg.camRotSensY
		);
	    }
	} else {
	    if(cur.rsActive(cfg.mouseDeadZone)) {
		emulateMouse(map, cur.rx * cfg.mouseSensX, cur.ry * cfg.mouseSensY);
	    }
	}

	// --- RS click: reset camera angle to north ---
	if(cur.rs && !prevRs)
	    map.camera.gpResetAngle();
	prevRs = cur.rs;

	// --- L1: toggle combat mode ---
	if(cur.l1 && !prevL1) {
	    cfg.combatMode = !cfg.combatMode;
	}
	prevL1 = cur.l1;

	// R1 / picker first so B-to-cancel is consumed before belt can fire
	dispatchR1(cur, prev, map);

	// ABXY belt hotbar — suppressed while radial picker is open
	if(!radialOpen)
	    dispatchBelt(cur, prev, map);
    }

    // -------------------------------------------------------------------------
    // Belt (ABXY)

    private void dispatchBelt(GamepadState cur, GamepadState prev, MapView map) {
	// Without L2: A=slot0, B=slot1, X=slot2, Y=slot3  (F1-F4)
	// With L2:    A=slot4, B=slot5, X=slot6, Y=slot7  (F5-F8)
	int offset = cur.l2Held ? 4 : 0;

	if(cur.btnA && !prev.btnA) activateBeltSlot(offset + 0);
	if(cur.btnB && !prev.btnB) activateBeltSlot(offset + 1);
	if(cur.btnX && !prev.btnX) activateBeltSlot(offset + 2);
	if(cur.btnY && !prev.btnY) activateBeltSlot(offset + 3);
    }

    private void activateBeltSlot(int slot) {
	if(gui.beltwdg != null)
	    gui.beltwdg.keyact(slot);
    }

    // -------------------------------------------------------------------------
    // R1 smart target / radial picker

    private void dispatchR1(GamepadState cur, GamepadState prev, MapView map) {
	boolean r1 = cur.r1;
	boolean r1Prev = prev.r1;

	if(r1 && !r1Prev) {
	    // Press: start timer
	    r1PressTime = System.currentTimeMillis();
	    r1WasHeld = false;
	}

	if(r1 && !r1WasHeld) {
	    long held = System.currentTimeMillis() - r1PressTime;
	    if(held >= cfg.r1HoldMs) {
		r1WasHeld = true;
		openRadialPicker(map);
	    }
	}

	if(!r1 && r1Prev) {
	    // Released
	    if(currentPicker != null && currentPicker.parent != null) {
		currentPicker.confirm();
		currentPicker = null;
		radialOpen = false;
	    } else if(!r1WasHeld) {
		// Tap: smart right-click
		smartClick(map);
	    }
	}

	// RS navigates open picker
	if(radialOpen && currentPicker != null && cur.rsActive(cfg.mouseDeadZone))
	    currentPicker.onStickAngle(cur.rsAngle());

	// B cancels picker
	if(radialOpen && currentPicker != null && cur.btnB && !prev.btnB) {
	    currentPicker.cancel();
	    currentPicker = null;
	    radialOpen = false;
	}
    }

    private void smartClick(MapView map) {
	Gob player = map.player();
	if(player == null) return;

	List<SmartTarget.Entry> hits = target.scan(map, player, 8);
	if(hits.isEmpty()) return;

	// If multiple entries share the top priority, open picker; else click best
	SmartTarget.Entry best = hits.get(0);
	long samePrio = hits.stream().filter(e -> e.priority == best.priority).count();

	if(samePrio > 1) {
	    openRadialPicker(map, hits);
	} else {
	    rightClickGob(map, best.gob);
	}
    }

    private void openRadialPicker(MapView map) {
	Gob player = map.player();
	if(player == null) return;
	List<SmartTarget.Entry> hits = target.scan(map, player, 8);
	openRadialPicker(map, hits);
    }

    private void openRadialPicker(MapView map, List<SmartTarget.Entry> hits) {
	if(hits.isEmpty()) return;
	if(radialOpen && currentPicker != null) return; // already open

	currentPicker = new RadialPicker(hits, entry -> {
	    rightClickGob(map, entry.gob);
	    radialOpen = false;
	    currentPicker = null;
	});
	map.adda(currentPicker, 0.5, 0.5);
	radialOpen = true;
    }

    /** Sends a right-click (button 3) on a specific Gob via the standard click path. */
    private void rightClickGob(MapView map, Gob gob) {
	Coord2d wc = gob.rc;
	Coord3f scr = map.screenxf(wc);
	Coord sc = (scr != null)
	    ? new Coord((int) scr.x, (int) scr.y)
	    : map.sz.div(2);

	// Matches Click.hit() + GobClick.clickargs():
	// {screenCoord, worldCoord, button, mods, overlayFlag, gobId, gobRc, overlayId, meshId}
	map.wdgmsg("click",
	    sc,
	    wc.floor(posres),
	    3,           // RMB
	    0,           // no modifiers
	    0,           // no overlay
	    (int) gob.id,
	    gob.rc.floor(posres),
	    0,           // overlayId
	    -1           // meshId (none)
	);
    }

    // -------------------------------------------------------------------------
    // Mouse emulation (RS without L2)

    private void emulateMouse(MapView map, float dx, float dy) {
	UI ui = gui.ui;
	if(ui == null) return;

	// Clamp new position within window bounds
	int nx = Math.max(0, Math.min(ui.root.sz.x - 1, ui.mc.x + (int) dx));
	int ny = Math.max(0, Math.min(ui.root.sz.y - 1, ui.mc.y + (int) dy));
	Coord nc = new Coord(nx, ny);

	if(nc.equals(ui.mc)) return;

	// Update tracked mouse position and dispatch a synthetic move event
	ui.mc = nc;
	ui.dispatch(ui.root, new Widget.MouseMoveEvent(nc));
    }
}
