package haven.gamepad;

import haven.*;

import java.awt.Color;
import java.awt.event.KeyEvent;
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
    private boolean r1PressedWithFlower = false; // R1 was pressed while FlowerMenu was open
    private boolean radialOpen = false;
    private RadialPicker currentPicker = null;

    // RS click edge detection (camera reset)
    private boolean prevRs = false;

    // L1 edge detection (LMB)
    private boolean prevL1 = false;

    // R2 / Select edge detection
    private boolean prevR2     = false;
    private boolean prevSelect = false;
    private InterfaceMenu interfaceMenu = null;
    private boolean interfaceMenuOpen = false;

    // D-pad menu-grid mode: active when D-pad last navigated MenuGrid
    private boolean gpMenuMode = false;
    // D-pad debounce: prevent double-firing from POV hat jitter
    private long lastDpadMs = 0;
    private static final long DPAD_DEBOUNCE_MS = 130;

    // Current best target for the frame (used by drawOverlay)
    private volatile Gob hoverGob = null;
    private Gob lastHoverGob = null;
    private long lastHoverTime = 0;

    // Window focus tracking for D-pad navigation
    private Window gpFocusedWindow = null;


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
	drawTargetIndicator(g, map);
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

    private Resource handCurs = null;
    private Text.Line hoverNameText = null;
    private String hoverNameLast = "";

    private Resource handCurs() {
	if(handCurs == null)
	    handCurs = Resource.local().loadwait("gfx/hud/curs/hand");
	return handCurs;
    }

    private void drawTargetIndicator(GOut g, MapView map) {
	Gob gob = hoverGob;
	if(gob == null) return;
	Coord3f scr = map.screenxf(gob.rc);
	if(scr == null) return;

	Resource curs = handCurs();
	Resource.Image imgLayer = curs.layer(Resource.imgc);
	Resource.Neg   negLayer = curs.layer(Resource.negc);
	if(imgLayer == null) return;

	Tex cursTeX   = imgLayer.tex();
	Coord hotspot = (negLayer != null) ? UI.scale(negLayer.cc) : Coord.z;

	// Draw cursor so its hotspot tip is just above the gob
	int cx = (int)scr.x;
	int cy = (int)scr.y - UI.scale(8);
	Coord drawPos = new Coord(cx - hotspot.x, cy - hotspot.y);
	g.image(cursTeX, drawPos);

	// Name label with FlowerMenu petal style
	String resName = SmartTarget.resName(gob);
	String displayName = gobDisplayName(resName);
	if(displayName == null) return;
	if(!displayName.equals(hoverNameLast)) {
	    hoverNameText = FlowerMenu.ptf.render(displayName, FlowerMenu.ptc);
	    hoverNameLast = displayName;
	}
	if(hoverNameText == null) return;

	Coord ns = hoverNameText.sz();
	int padX = UI.scale(12), padY = UI.scale(6);
	Coord labelSz = ns.add(padX * 2, padY * 2);
	// Label sits to the right of the cursor, vertically centered on the hotspot
	Coord labelPos = new Coord(drawPos.x + cursTeX.sz().x + UI.scale(4), cy - labelSz.y / 2);

	// Tiled background (same as FlowerMenu petal)
	g.chcolor(new Color(255, 255, 255, 210));
	Coord bgTL = labelPos.add(3, 3);
	Coord bgBR = labelPos.add(labelSz).sub(3, 3);
	g.image(FlowerMenu.pbg, bgTL, bgTL, bgBR, UI.scale(FlowerMenu.pbg.sz()));
	// Window-style border
	FlowerMenu.pbox.draw(g, labelPos, labelSz);
	// Text
	g.chcolor();
	g.image(hoverNameText.tex(), labelPos.add(labelSz.div(2)).sub(ns.div(2)));
    }

    private static String gobDisplayName(String resName) {
	if(resName == null || resName.isEmpty()) return null;
	String part = resName.substring(resName.lastIndexOf('/') + 1);
	if(part.isEmpty()) return null;
	return Character.toUpperCase(part.charAt(0)) + part.substring(1);
    }

    /** Called from GameUI.tick(). */
    public void tick(double dt) {
	MapView map = gui.map;
	if(map == null) return;

	GamepadState cur  = manager.getState();
	GamepadState prev = manager.getPrevState();

	// --- Window D-pad focus: follow gui.focused; retain last visible window ---
	Widget guiFoc = gui.focused;
	if(guiFoc instanceof Window && ((Window)guiFoc).visible())
	    gpFocusedWindow = (Window)guiFoc;
	else if(gpFocusedWindow != null && !gpFocusedWindow.visible())
	    gpFocusedWindow = null;
	boolean wndFocused = gpFocusedWindow != null;

	// --- Left stick: direct movement (also clears menu-grid mode) ---
	if(cur.lsActive(cfg.moveDeadZone)) {
	    gpMenuMode = false;
	    // Clamp click distance to avoid overshooting the current hover target.
	    // Using hoverGob from the previous tick (one-frame lag is fine).
	    double maxClickWU = -1;
	    Gob hover = hoverGob;
	    Gob playerGob = map.player();
	    if(hover != null && playerGob != null) {
		double dx = hover.rc.x - playerGob.rc.x;
		double dy = hover.rc.y - playerGob.rc.y;
		double d = Math.sqrt(dx * dx + dy * dy);
		if(d < cfg.clickDistTiles * DirectMovement.TILE * 1.5)
		    maxClickWU = Math.max(d - DirectMovement.TILE * 0.4, DirectMovement.TILE * 0.3);
	    }
	    movement.tick(map, cur, maxClickWU);
	    // Gaze tracks movement direction for smart targeting cone.
	    // Must match DirectMovement formula exactly (DualSense: negate both axes).
	    float camA = map.camera.angle();
	    float sinA = (float) Math.sin(camA), cosA = (float) Math.cos(camA);
	    float wdx = -cur.lx * sinA + cur.ly * cosA;
	    float wdy = -cur.lx * cosA - cur.ly * sinA;
	    target.updateGaze(wdx, wdy);
	}

	// --- Active overlay menus (priority: flower > interface > picker) ---
	FlowerMenu flower = FlowerMenu.active;
	boolean flowerOpen = flower != null && flower.parent != null;
	boolean interfaceOpen = interfaceMenu != null && interfaceMenu.parent != null;
	if(!interfaceOpen) interfaceMenuOpen = false; // widget destroyed externally

	// --- Right stick: menus > camera rotation (L2) > mouse emulation ---
	// DualSense RS: ry positive = up → negate for screen-space Y-down angle
	float rsAngle = (float)Math.atan2(-cur.ry, cur.rx);
	if(flowerOpen) {
	    if(cur.rsActive(cfg.mouseDeadZone))
		flower.gamepadSelect(rsAngle);
	} else if(interfaceOpen) {
	    if(cur.rsActive(cfg.mouseDeadZone))
		interfaceMenu.onStickAngle(rsAngle);
	} else if(radialOpen && currentPicker != null) {
	    if(cur.rsActive(cfg.mouseDeadZone))
		currentPicker.onStickAngle(rsAngle);
	} else if(cur.l2Held) {
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

	// --- RS click: reset camera (open world) or scroll down (menu/window) ---
	// --- LS click: scroll up ---
	if(cur.rs && !prevRs) {
	    if(wndFocused || gpMenuMode)
		scrollAtMouse(3);
	    else if(!flowerOpen && !interfaceOpen)
		map.camera.gpResetAngle();
	}
	prevRs = cur.rs;
	if(cur.ls && !prev.ls)
	    scrollAtMouse(-3);

	// --- Combat mode: mirror server state from Fightview ---
	cfg.combatMode = (gui.fv != null && !gui.fv.lsrel.isEmpty());

	// --- L1: left mouse button ---
	if(cur.l1 && !prevL1)
	    emulateMouseButton(1, true);
	else if(!cur.l1 && prevL1)
	    emulateMouseButton(1, false);
	prevL1 = cur.l1;

	// --- Start: ESC — directly closes focused window; falls back to key dispatch ---
	if(cur.start && !prev.start) {
	    if(wndFocused) {
		gpFocusedWindow.reqclose();
		gpFocusedWindow = null;
	    } else {
		dispatchKey(KeyEvent.VK_ESCAPE);
	    }
	}

	// --- Select: confirm inventory slot (LMB take) or MenuGrid ---
	if(cur.select && !prevSelect) {
	    if(wndFocused) {
		Inventory inv = findChild(gpFocusedWindow, Inventory.class);
		if(inv != null) inv.gpActivate();
	    } else if(gpMenuMode && gui.menu != null) {
		gui.menu.gpActivate();
	    }
	}
	prevSelect = cur.select;

	// --- R2: confirm FlowerMenu / InterfaceMenu, or open InterfaceMenu ---
	if(cur.r2Held && !prevR2) {
	    if(flowerOpen)
		flower.gamepadConfirm();
	    else if(interfaceOpen)
		interfaceMenu.confirm();
	    else if(!radialOpen) {
		interfaceMenu = new InterfaceMenu(gui);
		gui.add(interfaceMenu);
		interfaceMenuOpen = true;
	    }
	}
	prevR2 = cur.r2Held;

	// --- D-pad: priority dispatch ---
	// FlowerMenu > InterfaceMenu > RadialPicker > MenuGrid
	boolean dUp    = cur.dpadUp    && !prev.dpadUp;
	boolean dDown  = cur.dpadDown  && !prev.dpadDown;
	boolean dLeft  = cur.dpadLeft  && !prev.dpadLeft;
	boolean dRight = cur.dpadRight && !prev.dpadRight;
	long nowMs = System.currentTimeMillis();
	if(nowMs - lastDpadMs < DPAD_DEBOUNCE_MS)
	    dUp = dDown = dLeft = dRight = false; // debounce POV hat jitter
	if(dUp || dDown || dLeft || dRight) {
	    lastDpadMs = nowMs;
	    if(flowerOpen) {
		flower.gamepadDpad(dUp, dDown, dLeft, dRight);
	    } else if(interfaceOpen) {
		interfaceMenu.onDpad(dUp, dDown, dLeft, dRight);
	    } else if(radialOpen && currentPicker != null) {
		currentPicker.onDpad(dUp, dDown, dLeft, dRight);
	    } else if(wndFocused) {
		Inventory inv = findChild(gpFocusedWindow, Inventory.class);
		if(inv != null) {
		    if(dUp)         inv.gpMove(0, -1);
		    else if(dDown)  inv.gpMove(0,  1);
		    else if(dLeft)  inv.gpMove(-1, 0);
		    else if(dRight) inv.gpMove( 1, 0);
		} else {
		    if(dUp)        scrollAtMouse(-3);
		    else if(dDown) scrollAtMouse( 3);
		}
	    } else if(gui.menu != null) {
		// if/else if: ignore diagonals (POV hat can briefly report diagonal on press)
		if(dUp)         gui.menu.gpMove(0, -1);
		else if(dDown)  gui.menu.gpMove(0,  1);
		else if(dLeft)  gui.menu.gpMove(-1, 0);
		else if(dRight) gui.menu.gpMove( 1, 0);
		gpMenuMode = true;
	    }
	}

	// --- B: cancel the highest-priority active menu; A: hotbar only ---
	if(flowerOpen) {
	    if(cur.btnB && !prev.btnB) flower.gamepadCancel();
	} else if(interfaceOpen) {
	    if(cur.btnB && !prev.btnB) interfaceMenu.cancel();
	} else if(wndFocused) {
	    if(cur.btnB && !prev.btnB) {
		Inventory inv = findChild(gpFocusedWindow, Inventory.class);
		if(inv != null && inv.gpX >= 0) {
		    inv.gpX = -1;  // deselect slot first press
		    inv.gpY = -1;
		} else {
		    gpFocusedWindow.reqclose();
		    gpFocusedWindow = null;
		}
	    }
	} else if(gpMenuMode) {
	    if(cur.btnB && !prev.btnB) gpMenuMode = false;
	}

	// R1 / picker (B-cancel handled inside, before belt can steal it)
	dispatchR1(cur, prev, map);

	// When a window is focused: A=ENTER (override hotbar)
	if(wndFocused) {
	    if(cur.btnA && !prev.btnA) dispatchKey(KeyEvent.VK_ENTER);
	}

	// ABXY belt hotbar — suppressed in any menu mode or focused window
	if(!radialOpen && !flowerOpen && !interfaceOpen && !gpMenuMode && !wndFocused)
	    dispatchBelt(cur, prev, map);

	// Update hover target for overlay drawing.
	// Grace period: keep the ring on the last-seen target for 1.5 s after the cone
	// loses it (handles the common case where the player stops just past the target).
	Gob player = map.player();
	if(radialOpen || interfaceOpen) {
	    hoverGob = null;
	    lastHoverGob = null;
	} else if(player != null) {
	    SmartTarget.Entry best = target.best(map, player);
	    if(best != null) {
		hoverGob = best.gob;
		lastHoverGob = best.gob;
		lastHoverTime = System.currentTimeMillis();
	    } else if(System.currentTimeMillis() - lastHoverTime < 200) {
		hoverGob = lastHoverGob; // keep ring visible one or two frames after losing cone lock
	    } else {
		hoverGob = null;
		lastHoverGob = null;
	    }
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
	    r1PressTime = System.currentTimeMillis();
	    r1WasHeld = false;
	    // Snapshot: was FlowerMenu open when R1 was pressed?
	    FlowerMenu fl0 = FlowerMenu.active;
	    r1PressedWithFlower = (fl0 != null && fl0.parent != null);
	}

	// Only start hold-timer (for RadialPicker) when FlowerMenu was NOT open at press time.
	if(r1 && !r1WasHeld && !r1PressedWithFlower) {
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
		FlowerMenu fl = FlowerMenu.active;
		if(fl != null && fl.parent != null) {
		    fl.gamepadConfirm();
		} else if(!r1PressedWithFlower) {
		    // Only smartClick when R1 was NOT pressed over an open FlowerMenu.
		    // Prevents re-clicking the same object while the close animation plays
		    // (choose() nulls FlowerMenu.active immediately, before the 0.75s animation ends).
		    smartClick(map);
		}
	    }
	}

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
	gui.add(currentPicker);
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

    // -------------------------------------------------------------------------
    // Widget utilities

    // Dummy AWT component required as source for synthetic KeyEvents.
    private final java.awt.Label dummyComp = new java.awt.Label();

    /**
     * Dispatches a synthetic key exactly as UI.keydown does:
     * KeyDownEvent first; if not consumed, GlobKeyEvent (global hotkeys like craft buttons).
     */
    private void dispatchKey(int keyCode) {
	UI ui = gui.ui;
	if(ui == null) return;
	KeyEvent kd = new KeyEvent(dummyComp, KeyEvent.KEY_PRESSED,
	    System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED);
	KeyEvent ku = new KeyEvent(dummyComp, KeyEvent.KEY_RELEASED,
	    System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED);
	if(!ui.dispatch(ui.root, new Widget.KeyDownEvent(kd)))
	    ui.dispatch(ui.root, new Widget.GlobKeyEvent(kd));
	ui.dispatch(ui.root, new Widget.KeyUpEvent(ku));
    }

    /** Dispatches a scroll-wheel event at the current mouse cursor position. */
    private void scrollAtMouse(int amount) {
	UI ui = gui.ui;
	if(ui == null) return;
	ui.dispatch(ui.root, new Widget.MouseWheelEvent(ui.mc, amount));
    }

    /** Recursively finds the first child widget of the given class. */
    @SuppressWarnings("unchecked")
    private static <T extends Widget> T findChild(Widget root, Class<T> cls) {
	for(Widget w = root.child; w != null; w = w.next) {
	    if(cls.isInstance(w)) return (T)w;
	    T found = findChild(w, cls);
	    if(found != null) return found;
	}
	return null;
    }

}
