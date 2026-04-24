package haven.gamepad;

import haven.Utils;

public class GamepadConfig {
    // Movement
    public float moveDeadZone    = 0.15f;
    /** World-units per click ahead of player (2 tiles ≈ 22 world units). */
    public float clickDistTiles  = 2.0f;
    /** Milliseconds between movement click pulses. */
    public long  clickIntervalMs = 150;

    // Camera rotation (L2 + RS)
    public float camRotSensX = 0.04f;  // radians per full-deflection tick
    public float camRotSensY = 0.02f;
    public float camMinElev  = (float)(Math.PI / 16.0); // ~11 deg
    public float camMaxElev  = (float)(Math.PI / 2.0 - 0.01);

    // Mouse emulation (RS without L2)
    public float mouseSensX  = 6.0f;   // px per full-deflection tick
    public float mouseSensY  = 6.0f;
    public float mouseDeadZone = 0.12f;

    // Trigger threshold for "held" state
    public float triggerThreshold = 0.5f;

    // Smart target
    public float targetConeHalfDeg  = 30f;
    public float targetConeDepthTiles = 7f;
    /** Hold duration in ms before R1 opens RadialPicker instead of smart-clicking. */
    public long  r1HoldMs = 350;

    // Combat mode
    public boolean combatMode = false;

    public static GamepadConfig load() {
	GamepadConfig cfg = new GamepadConfig();
	cfg.moveDeadZone     = (float)Utils.getprefd("gp.moveDeadZone",     cfg.moveDeadZone);
	cfg.clickDistTiles   = (float)Utils.getprefd("gp.clickDistTiles",   cfg.clickDistTiles);
	cfg.clickIntervalMs  = Utils.getprefi("gp.clickIntervalMs",          (int)cfg.clickIntervalMs);
	cfg.camRotSensX      = (float)Utils.getprefd("gp.camRotSensX",      cfg.camRotSensX);
	cfg.camRotSensY      = (float)Utils.getprefd("gp.camRotSensY",      cfg.camRotSensY);
	cfg.mouseSensX       = (float)Utils.getprefd("gp.mouseSensX",       cfg.mouseSensX);
	cfg.mouseSensY       = (float)Utils.getprefd("gp.mouseSensY",       cfg.mouseSensY);
	cfg.mouseDeadZone    = (float)Utils.getprefd("gp.mouseDeadZone",    cfg.mouseDeadZone);
	cfg.triggerThreshold = (float)Utils.getprefd("gp.triggerThreshold", cfg.triggerThreshold);
	cfg.r1HoldMs         = Utils.getprefi("gp.r1HoldMs",                (int)cfg.r1HoldMs);
	return cfg;
    }

    public void save() {
	Utils.setprefd("gp.moveDeadZone",     moveDeadZone);
	Utils.setprefd("gp.clickDistTiles",   clickDistTiles);
	Utils.setprefi("gp.clickIntervalMs",  (int)clickIntervalMs);
	Utils.setprefd("gp.camRotSensX",      camRotSensX);
	Utils.setprefd("gp.camRotSensY",      camRotSensY);
	Utils.setprefd("gp.mouseSensX",       mouseSensX);
	Utils.setprefd("gp.mouseSensY",       mouseSensY);
	Utils.setprefd("gp.mouseDeadZone",    mouseDeadZone);
	Utils.setprefd("gp.triggerThreshold", triggerThreshold);
	Utils.setprefi("gp.r1HoldMs",         (int)r1HoldMs);
    }
}
