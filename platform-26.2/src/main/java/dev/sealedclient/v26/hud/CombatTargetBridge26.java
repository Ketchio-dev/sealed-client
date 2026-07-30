package dev.sealedclient.v26.hud;

/**
 * The entity the combat modules actually chose this tick.
 *
 * <p>The Target HUD previously showed whatever the crosshair happened to be
 * over, which is not what KillAura or AutoCrystal act on: both pick a target by
 * range and health rather than by look direction. This bridge carries the real
 * selection so the readout matches the module that is running.</p>
 *
 * <p>Only an entity id and a source label are stored — never an entity
 * reference — so a stale target can never keep a removed entity alive or be
 * read after the level is gone.</p>
 */
public final class CombatTargetBridge26 {
    /** Entity id meaning "no combat module selected anything". */
    public static final int NO_TARGET = -1;

    private int entityId = NO_TARGET;
    private Source source = Source.NONE;
    private int observedTick = Integer.MIN_VALUE;

    /**
     * Records the selection made on {@code tick}. A null or absent selection
     * clears the bridge, so the HUD stops showing a target the moment the
     * modules stop choosing one.
     */
    public void observe(int entityId, Source source, int tick) {
        if (entityId < 0 || source == null || source == Source.NONE) {
            clear();
            return;
        }
        this.entityId = entityId;
        this.source = source;
        this.observedTick = tick;
    }

    /**
     * The selected entity id, or {@link #NO_TARGET} when the selection is
     * absent or older than {@code currentTick}. Requiring the current tick
     * means a target that was chosen once never lingers on the HUD.
     */
    public int entityId(int currentTick) {
        return observedTick == currentTick ? entityId : NO_TARGET;
    }

    public Source source(int currentTick) {
        return observedTick == currentTick ? source : Source.NONE;
    }

    public void clear() {
        entityId = NO_TARGET;
        source = Source.NONE;
        observedTick = Integer.MIN_VALUE;
    }

    /** Which combat module made the selection. */
    public enum Source {
        NONE(""),
        KILL_AURA("Aura"),
        TRIGGER_BOT("Trigger"),
        AUTO_CRYSTAL("Crystal");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
