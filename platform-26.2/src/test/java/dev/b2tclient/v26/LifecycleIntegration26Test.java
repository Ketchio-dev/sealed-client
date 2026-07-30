package dev.b2tclient.v26;

import dev.b2tclient.common.module.ModuleKeybindDispatcher;
import dev.b2tclient.common.module.ModuleRegistry;
import dev.b2tclient.common.module.ModuleSnapshot;
import dev.b2tclient.common.module.RegisteredModule;
import dev.b2tclient.common.profile.ProfileBook;
import dev.b2tclient.v26.config.ConfigStore26;
import dev.b2tclient.v26.config.PresetApplication26;
import dev.b2tclient.v26.config.PresetCatalog26;
import dev.b2tclient.v26.hud.CombatTargetBridge26;
import dev.b2tclient.v26.hud.HudLayout26;
import dev.b2tclient.common.social.FriendBook;
import dev.b2tclient.common.waypoint.WaypointBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-component integration over the 26.2 state that a connect / configure /
 * disconnect cycle touches: profiles, presets, keybinds, the HUD layout, the
 * combat target bridge, and the config store.
 *
 * <p>These are the collaborations {@code ClientRuntime26} orchestrates. The
 * runtime class itself needs a live Minecraft instance and is therefore covered
 * by booting the client, not here.</p>
 */
class LifecycleIntegration26Test {
    @TempDir
    Path temporaryDirectory;

    private final ModuleRegistry modules = PlatformCapabilities26.createRegistry();
    private final ProfileBook profiles = new ProfileBook();
    private final FriendBook friends = new FriendBook();
    private final WaypointBook waypoints = new WaypointBook();
    private final HudLayout26 hudLayout = new HudLayout26();
    private final PresetApplication26 presets = new PresetApplication26();
    private final ModuleKeybindDispatcher keybinds = new ModuleKeybindDispatcher();
    private final CombatTargetBridge26 combatTarget = new CombatTargetBridge26();
    private final Set<Integer> keysDown = new HashSet<>();

    private ConfigStore26 store() {
        return new ConfigStore26(temporaryDirectory.resolve("b2tclient-26.2.json"));
    }

    private List<RegisteredModule> dispatch(boolean screenOpen) {
        return keybinds.pressedThisTick(modules.all(), keysDown::contains, screenOpen);
    }

    /** Mirrors the teardown {@code ClientRuntime26.releasePlatformState} performs. */
    private void disconnect() {
        keybinds.reset();
        combatTarget.clear();
    }

    @Test
    void aFullConnectConfigureDisconnectCycleLeavesNoSessionStateBehind() throws Exception {
        RegisteredModule clock = modules.find("clock").orElseThrow();
        clock.setKeyCode(70);

        // Connect: the server profile is applied.
        profiles.capture("2b2t", "2b2t.org", modules);
        assertTrue(profiles.activateBestMatchForServer("2b2t.org", modules));

        // In-session: a keybind toggles a module.
        assertFalse(clock.enabled());
        keysDown.add(70);
        assertEquals(List.of(clock), dispatch(false));
        clock.toggle();
        assertTrue(clock.enabled());

        // In-session: combat selects a target.
        combatTarget.observe(11, CombatTargetBridge26.Source.KILL_AURA, 5);
        assertEquals(11, combatTarget.entityId(5));

        // Disconnect.
        disconnect();

        assertEquals(0, keybinds.heldKeyCount(),
                "a key held at disconnect must not survive into the next session");
        assertEquals(CombatTargetBridge26.NO_TARGET, combatTarget.entityId(5),
                "the previous server's target must not persist");

        // Reconnect with the key still physically down: no phantom toggle.
        assertEquals(List.of(clock), dispatch(false),
                "after a reset the still-held key reads as a fresh press");
    }

    @Test
    void reconnectingReappliesTheProfileEvenWhenItIsAlreadyActive() {
        profiles.capture("2b2t", "2b2t.org", modules);
        assertTrue(profiles.activateBestMatchForServer("2b2t.org", modules));
        Map<String, ModuleSnapshot> saved = modules.snapshot();

        // Live mutation during the session that was never saved to the profile.
        modules.find("clock").orElseThrow().setEnabled(true);
        assertFalse(saved.equals(modules.snapshot()));

        disconnect();
        assertTrue(profiles.activateBestMatchForServer("2b2t.org", modules));
        assertEquals(saved, modules.snapshot(),
                "an unsaved live change must not survive a reconnect");
    }

    @Test
    void aPresetAppliedInSessionRoundTripsThroughTheConfigAndCanBeUndone() throws Exception {
        Map<String, ModuleSnapshot> before = modules.snapshot();
        assertTrue(presets.apply(
                PresetCatalog26.find(PresetCatalog26.LOW_LAG_UTILITY_ID).orElseThrow(),
                modules
        ).isEmpty());
        hudLayout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(0.4, 0.6));

        ConfigStore26 store = store();
        store.save(modules, profiles, friends, waypoints, hudLayout);

        ModuleRegistry reloaded = PlatformCapabilities26.createRegistry();
        HudLayout26 reloadedLayout = new HudLayout26();
        assertEquals(
                ConfigStore26.LoadResult.LOADED,
                store.load(reloaded, new ProfileBook(), new FriendBook(),
                        new WaypointBook(), reloadedLayout)
        );
        assertEquals(modules.snapshot(), reloaded.snapshot(),
                "the applied preset must survive a save and load");
        assertEquals(0.4, reloadedLayout.anchor(HudLayout26.Panel.INFO).xFraction());

        assertTrue(presets.undo(modules).isEmpty());
        assertEquals(before, modules.snapshot());
    }

    @Test
    void switchingProfilesInvalidatesAPendingPresetUndo() {
        profiles.capture("first", "*", modules);
        assertTrue(presets.apply(
                PresetCatalog26.find(PresetCatalog26.LOW_LAG_UTILITY_ID).orElseThrow(),
                modules
        ).isEmpty());
        assertTrue(presets.canUndo());

        // A profile switch replaces every module state, so the undo baseline is
        // no longer meaningful — the runtime clears it for exactly this reason.
        profiles.capture("second", "2b2t.org", modules);
        assertTrue(profiles.activateBestMatchForServer("2b2t.org", modules));
        presets.clear();

        assertFalse(presets.canUndo());
    }

    @Test
    void keybindsPersistAcrossASaveAndLoadAndStayWithinRange() throws Exception {
        modules.find("clock").orElseThrow().setKeyCode(70);
        modules.find("fps").orElseThrow().setKeyCode(RegisteredModule.MAX_KEY_CODE + 500);

        ConfigStore26 store = store();
        store.save(modules, profiles, friends, waypoints, hudLayout);

        ModuleRegistry reloaded = PlatformCapabilities26.createRegistry();
        assertEquals(
                ConfigStore26.LoadResult.LOADED,
                store.load(reloaded, new ProfileBook(), new FriendBook(),
                        new WaypointBook(), new HudLayout26())
        );
        assertEquals(70, reloaded.find("clock").orElseThrow().keyCode());
        assertEquals(
                RegisteredModule.UNBOUND_KEY_CODE,
                reloaded.find("fps").orElseThrow().keyCode(),
                "an out-of-range persisted keybind must not resolve to a real key"
        );
    }

    @Test
    void aScreenOpenSwallowsKeybindsForEveryModule() {
        modules.find("clock").orElseThrow().setKeyCode(70);
        modules.find("fps").orElseThrow().setKeyCode(71);
        keysDown.add(70);
        keysDown.add(71);

        assertTrue(dispatch(true).isEmpty(), "typing in a screen must never toggle modules");
        assertTrue(dispatch(false).isEmpty(), "still held, so still no rising edge");
    }
}
