package haven.gamepad;

import haven.*;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;

import static haven.FlowerMenu.*;

/**
 * Gamepad radial object picker styled after vanilla FlowerMenu.
 *
 * Usage:
 *   - Opens with a list of SmartTarget.Entry items (max 8).
 *   - RS angle selects a sector; the selected petal is highlighted.
 *   - R1 release (or A button) confirms the selection → calls onSelect.
 *   - B button cancels.
 *
 * Visual: reuses FlowerMenu textures / colours so it looks vanilla.
 */
public class RadialPicker extends Widget {
    private static final int MAX_ITEMS = 8;
    private static final double TWO_PI = 2 * Math.PI;

    private final Petal[] petals;
    private int selectedIdx = 0;
    private final Consumer<SmartTarget.Entry> onSelect;
    private UI.Grab mg, kg;

    public RadialPicker(List<SmartTarget.Entry> entries, Consumer<SmartTarget.Entry> onSelect) {
	super(Coord.z);
	this.onSelect = onSelect;

	int count = Math.min(entries.size(), MAX_ITEMS);
	petals = new Petal[count];
	for(int i = 0; i < count; i++) {
	    SmartTarget.Entry e = entries.get(i);
	    String label = label(e);
	    petals[i] = new Petal(label, e, i, count);
	    add(petals[i]);
	}
	placePetals();
    }

    @Override
    protected void added() {
	// ui is available here (set by Widget.add before calling added())
	mg = ui.grabmouse(this);
	kg = ui.grabkeys(this);
	animateOpen();
    }

    // -------------------------------------------------------------------------
    // Gamepad API (called from GameUI dispatcher)

    /** Update selected sector from RS angle. */
    public void onStickAngle(float angle) {
	if(petals.length == 0) return;
	// Shift so that angle 0 (right) maps to sector 0; normalize to [0, 2PI)
	double norm = ((angle % TWO_PI) + TWO_PI) % TWO_PI;
	double sectorSize = TWO_PI / petals.length;
	int idx = (int)(norm / sectorSize) % petals.length;
	setSelected(idx);
    }

    /** Confirm current selection (R1 release or A button). */
    public void confirm() {
	if(selectedIdx >= 0 && selectedIdx < petals.length)
	    choose(petals[selectedIdx]);
    }

    /** Cancel without selecting (B button). */
    public void cancel() {
	close();
    }

    // -------------------------------------------------------------------------

    private void setSelected(int idx) {
	selectedIdx = idx;
    }

    private void choose(Petal p) {
	close();
	onSelect.accept(p.entry);
    }

    private void close() {
	if(mg != null) mg.remove();
	if(kg != null) kg.remove();
	ui.destroy(this);
    }

    private void placePetals() {
	double baseRadius = UI.scale(80);
	for(int i = 0; i < petals.length; i++) {
	    double angle = (Math.PI / 2) - (i * TWO_PI / petals.length);
	    petals[i].moveTo(angle, baseRadius);
	}
    }

    private void animateOpen() {
	for(Petal p : petals) {
	    p.a = 0;
	    p.targetA = 1;
	}
    }

    @Override
    public void draw(GOut g) {
	// Draw connecting lines from center to each petal (subtle)
	for(int i = 0; i < petals.length; i++) {
	    Petal p = petals[i];
	    if(i == selectedIdx)
		g.chcolor(new Color(255, 220, 80, (int)(180 * p.a)));
	    else
		g.chcolor(new Color(255, 255, 255, (int)(60 * p.a)));
	    Coord from = Coord.z;
	    Coord to = p.c.add(p.sz.div(2));
	    g.line(from, to, 1);
	}
	g.chcolor();
	super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
	return true; // consume — prevent map clicks while picker is open
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
	if(ev.code == java.awt.event.KeyEvent.VK_ESCAPE) {
	    cancel();
	    return true;
	}
	return false;
    }

    // -------------------------------------------------------------------------

    private static String label(SmartTarget.Entry e) {
	String rn = e.resName;
	if(rn == null || rn.isEmpty()) return "Object";
	// Extract last path segment as a readable name
	int slash = rn.lastIndexOf('/');
	String name = (slash >= 0) ? rn.substring(slash + 1) : rn;
	// Capitalise first letter
	if(name.isEmpty()) return "Object";
	return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // -------------------------------------------------------------------------

    public class Petal extends Widget {
	final SmartTarget.Entry entry;
	final int index;
	double a = 1.0;       // current alpha
	double targetA = 1.0;
	private double angle, radius;
	private final Text text;

	Petal(String name, SmartTarget.Entry entry, int index, int total) {
	    super(Coord.z);
	    this.entry = entry;
	    this.index = index;
	    text = ptf.render(name, ptc);
	    resize(text.sz().x + UI.scale(25), ph);
	}

	void moveTo(double angle, double radius) {
	    this.angle = angle;
	    this.radius = radius;
	    Coord center = Coord.sc(angle, radius);
	    c = center.sub(sz.div(2));
	}

	@Override
	public void tick(double dt) {
	    // Smoothly animate alpha
	    a += (targetA - a) * Math.min(1.0, dt * 10.0);
	}

	@Override
	public void draw(GOut g) {
	    boolean sel = (index == selectedIdx);
	    int alpha = (int)(255 * a);
	    g.chcolor(new Color(255, 255, 255, alpha));
	    g.image(pbg, new Coord(3, 3), new Coord(3, 3),
		    sz.add(new Coord(-6, -6)), UI.scale(pbg.sz()));
	    pbox.draw(g, Coord.z, sz);
	    if(sel) {
		// Highlight selected petal
		g.chcolor(new Color(255, 220, 80, alpha / 2));
		g.frect(new Coord(2, 2), sz.sub(4, 4));
	    }
	    g.chcolor(new Color(255, 255, 255, alpha));
	    g.image(text.tex(), sz.div(2).sub(text.sz().div(2)));
	    g.chcolor();
	}

	@Override
	public boolean mousedown(MouseDownEvent ev) {
	    choose(this);
	    return true;
	}
    }
}
