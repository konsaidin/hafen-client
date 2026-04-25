package haven.gamepad;

import haven.*;

import java.awt.Color;

import static haven.FlowerMenu.*;

/**
 * Client-side radial menu bound to R2. Provides quick access to UI windows:
 * Map, Character, Kin, Inventory, Equipment.
 *
 * Navigation: RS angle or D-pad to select, A/R2 to confirm, B to cancel.
 */
public class InterfaceMenu extends Widget {
    private static final String[] LABELS = {"Map", "Character", "Kin", "Inventory", "Equipment"};
    private static final String[] IDS    = {"map", "chr",       "kin", "inv",       "equ"};
    private static final double TWO_PI   = 2 * Math.PI;

    private final GameUI gui;
    private final Entry[] entries;
    private int selectedIdx = 0;
    private UI.Grab mg, kg;

    public InterfaceMenu(GameUI gui) {
	super(Coord.z);
	this.gui = gui;
	entries = new Entry[LABELS.length];
	for(int i = 0; i < LABELS.length; i++) {
	    entries[i] = new Entry(LABELS[i], i);
	    add(entries[i]);
	}
	// placePetals() called in added() once parent size is known
    }

    @Override
    protected void added() {
	c = parent.sz.div(2); // widget origin = screen center; sz stays (0,0)
	mg = ui.grabmouse(this);
	kg = ui.grabkeys(this);
	placePetals();
    }

    // -------------------------------------------------------------------------
    // Gamepad API

    public void onStickAngle(float angle) {
	if(entries.length == 0) return;
	// Nearest-angle search — works correctly for any number of petals.
	// Entry i is placed at angle PI/2 - i * 2PI/N (screen-space, Y-down).
	int best = 0;
	float bestDiff = Float.MAX_VALUE;
	for(int i = 0; i < entries.length; i++) {
	    float ta = (float)((Math.PI / 2) - i * TWO_PI / entries.length);
	    float diff = Math.abs(angleDiff(ta, angle));
	    if(diff < bestDiff) { bestDiff = diff; best = i; }
	}
	selectedIdx = best;
    }

    /** D-pad: map cardinal direction to nearest entry by angle. */
    public void onDpad(boolean up, boolean down, boolean left, boolean right) {
	if(up)         onStickAngle(-(float)Math.PI / 2);
	else if(down)  onStickAngle( (float)Math.PI / 2);
	else if(right) onStickAngle(0f);
	else if(left)  onStickAngle( (float)Math.PI);
    }

    private static float angleDiff(float a, float b) {
	float d = a - b;
	while(d >  Math.PI) d -= (float)(2 * Math.PI);
	while(d < -Math.PI) d += (float)(2 * Math.PI);
	return d;
    }

    public void confirm() {
	if(selectedIdx >= 0 && selectedIdx < IDS.length)
	    gui.gpToggleUI(IDS[selectedIdx]);
	close();
    }

    public void cancel() {
	close();
    }

    // -------------------------------------------------------------------------

    private void close() {
	if(mg != null) { mg.remove(); mg = null; }
	if(kg != null) { kg.remove(); kg = null; }
	ui.destroy(this);
    }

    private void placePetals() {
	double r = UI.scale(80);
	for(int i = 0; i < entries.length; i++) {
	    double a = (Math.PI / 2) - (i * TWO_PI / entries.length);
	    entries[i].c = Coord.sc(a, r).sub(entries[i].sz.div(2));
	}
    }

    @Override
    public void draw(GOut g) {
	for(int i = 0; i < entries.length; i++) {
	    Entry e = entries[i];
	    if(i == selectedIdx)
		g.chcolor(new Color(80, 200, 255, 180));
	    else
		g.chcolor(new Color(255, 255, 255, 60));
	    g.line(Coord.z, e.c.add(e.sz.div(2)), 1);
	}
	g.chcolor();
	super.draw(g, false);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
	return true;
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
	if(key_esc.match(ev)) { cancel(); return true; }
	return false;
    }

    // -------------------------------------------------------------------------

    public class Entry extends Widget {
	final int index;
	private final Text text;

	Entry(String label, int index) {
	    super(Coord.z);
	    this.index = index;
	    text = ptf.render(label, ptc);
	    resize(text.sz().x + UI.scale(25), ph);
	}

	@Override
	public void draw(GOut g) {
	    boolean sel = (index == selectedIdx);
	    g.chcolor(new Color(255, 255, 255, 255));
	    g.image(pbg, new Coord(3, 3), new Coord(3, 3),
		    sz.add(new Coord(-6, -6)), UI.scale(pbg.sz()));
	    pbox.draw(g, Coord.z, sz);
	    if(sel) {
		g.chcolor(new Color(80, 200, 255, 100));
		g.frect(new Coord(3, 3), sz.add(new Coord(-6, -6)));
		g.chcolor(new Color(255, 255, 255, 255));
	    }
	    g.image(text.tex(), sz.div(2).sub(text.sz().div(2)));
	    g.chcolor();
	}

	@Override
	public boolean mousedown(MouseDownEvent ev) {
	    selectedIdx = index;
	    confirm();
	    return true;
	}
    }
}
