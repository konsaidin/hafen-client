package haven.gamepad;

import haven.*;

import java.awt.Color;
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

    // RS click edge detection (camera reset)
    private boolean prevRs = false;

    // L1 edge detection (LMB)
    private boolean prevL1 = false;

    // Current best target for the frame (used by drawOverlay)
    private volatile Gob hoverGob = null;

    private int debugTick = 0;
    private Text debugText = null;
    private String debugLast = "";

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

    /**
     * Called from GameUI.draw() to render 2D gamepad overlays on top of the scene:
     *  - A target ring around the current best SmartTarget candidate
     *  - A COMBAT MODE badge when combat mode is active
     */
    public void drawOverlay(GOut g, MapView map) {
	drawTargetRing(g, map);
	drawHUDBadges(g);
    }

    private static final Text combatText  = Text.render("⚔ COMBAT");
    private static final Text gamepadText = Text.render("⌖ GAMEPAD");

    private void drawHUDBadges(GOut g) {
	int x = UI.scale(8);
	int y = UI.scale(8);
	int pad = UI.scale(4);
	if(manager.isConnected()) {
	    Coord ts = gamepadText.sz();
	    g.chcolor(new Color(0, 0, 0, 160));
	    g.frect(new Coord(x - pad, y - pad / 2), ts.add(pad * 2, pad));
	    g.chcolor(new Color(120, 200, 120, 230));
	    g.image(gamepadText.tex(), new Coord(x, y));
	    y += ts.y + pad;

	    // Debug HUD: raw axis/button state
	    GamepadState s = manager.getState();
	    String dbg = String.format("L1:%d R1:%d L2:%.2f RX:%.2f RY:%.2f",
		s.l1 ? 1 : 0, s.r1 ? 1 : 0, s.l2, s.rx, s.ry);
	    if(!dbg.equals(debugLast)) {
		debugText = Text.render(dbg);
		debugLast = dbg;
	    }
	    if(debugText != null) {
		Coord ds = debugText.sz();
		g.chcolor(new Color(0, 0, 0, 160));
		g.frect(new Coord(x - pad, y - pad / 2), ds.add(pad * 2, pad));
		g.chcolor(new Color(200, 200, 80, 230));
		g.image(debugText.tex(), new Coord(x, y));
		y += ds.y + pad;
	    }
	}
	if(cfg.combatMode) {
	    Coord ts = combatText.sz();
	    g.chcolor(new Color(0, 0, 0, 160));
	    g.frect(new Coord(x - pad, y - pad / 2), ts.add(pad * 2, pad));
	    g.chcolor(new Color(220, 60, 60, 230));
	    g.image(combatText.tex(), new Coord(x, y));
	}
	g.chcolor();
    }

    private static final int RING_R = UI.scale(20);
    private static final int RING_SEGS = 16;

    private void drawTargetRing(GOut g, MapView map) {
	Gob gob = hoverGob;
	if(gob == null) return;
	Coord3f scr = map.screenxf(gob.rc);
	if(scr == null) return;
	Coord center = new Coord((int) scr.x, (int) scr.y);

	g.chcolor(new Color(80, 220, 255, 200));
	double step = 2 * Math.PI / RING_SEGS;
	for(int i = 0; i < RING_SEGS; i++) {
	    double a0 = i * step;
	    double a1 = (i + 1) * step;
	    Coord p0 = center.add((int)(Math.cos(a0) * RING_R), (int)(Math.sin(a0) * RING_R));
	    Coord p1 = center.add((int)(Math.cos(a1) * RING_R), (int)(Math.sin(a1) * RING_R));
	    g.line(p0, p1, 2.0);
	}
	g.chcolor();
    }

    /** Called from GameUI.tick(). */
    public void tick(double dt) {
	MapView map = gui.map;
	if(map == null) return;

	GamepadState cur  = manager.getState();
	GamepadState prev = manager.getPrevState();

	// Debug: dump raw state every ~2 seconds so we can diagnose button/axis mapping
	if(manager.isConnected() && ++debugTick % 120 == 0) {
	    System.err.printf("[GP] l1=%b r1=%b l2=%.2f r2=%.2f rx=%.2f ry=%.2f l2h=%b A=%b B=%b X=%b Y=%b%n",
		cur.l1, cur.r1, cur.l2, cur.r2, cur.rx, cur.ry, cur.l2Held,
		cur.btnA, cur.btnB, cur.btnX, cur.btnY);
	}

	// --- Left stick: direct movement ---
	if(cur.lsActive(cfg.moveDeadZone)) {
	    movement.tick(map, cur);
	    // Track gaze direction for smart target — same rotation math as DirectMovement
	    float camA = map.camera.angle();
	    float sinA = (float) Math.sin(camA), cosA = (float) Math.cos(camA);
	    float wdx = cur.lx * sinA + cur.ly * cosA;
	    float wdy = cur.lx * cosA - cur.ly * sinA;
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

	// --- Combat mode: mirror server state from Fightview ---
	cfg.combatMode = (gui.fv != null && !gui.fv.lsrel.isEmpty());

	// --- L1: left mouse button ---
	if(cur.l1 && !prevL1) {
	    System.err.println("[GP] L1 down → LMB");
	    emulateMouseButton(1, true);
	} else if(!cur.l1 && prevL1) {
	    System.err.println("[GP] L1 up → LMB release");
	    emulateMouseButton(1, false);
	}
	prevL1 = cur.l1;

	// R1 / picker first so B-to-cancel is consumed before belt can fire
	dispatchR1(cur, prev, map);

	// ABXY belt hotbar — suppressed while radial picker is open
	if(!radialOpen)
	    dispatchBelt(cur, prev, map);

	// Update hover target for overlay drawing
	Gob player = map.player();
	if(player != null && !radialOpen) {
	    SmartTarget.Entry best = target.best(map, player);
	    hoverGob = (best != null) ? best.gob : null;
	} else if(radialOpen) {
	    hoverGob = null;
	}
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
	    System.err.println("[GP] R1 pressed");
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
	if(player == null) {
	    // Fallback: right-click at cursor position
	    rightClickAtCursor();
	    return;
	}

	List<SmartTarget.Entry> hits = target.scan(map, player, 8);
	if(hits.isEmpty()) {
	    // No target in cone — fall back to cursor right-click
	    rightClickAtCursor();
	    return;
	}

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

    private void emulateMouseButton(int btn, boolean down) {
	UI ui = gui.ui;
	if(ui == null) return;
	Coord mc = ui.mc;
	if(down) {
	    ui.lcc = mc;
	    ui.dispatch(ui.root, new Widget.MouseDownEvent(mc, btn));
	} else {
	    ui.dispatch(ui.root, new Widget.MouseUpEvent(mc, btn));
	}
    }

    private void rightClickAtCursor() {
	UI ui = gui.ui;
	if(ui == null) return;
	Coord mc = ui.mc;
	ui.lcc = mc;
	ui.dispatch(ui.root, new Widget.MouseDownEvent(mc, 3));
	ui.dispatch(ui.root, new Widget.MouseUpEvent(mc, 3));
    }
}
