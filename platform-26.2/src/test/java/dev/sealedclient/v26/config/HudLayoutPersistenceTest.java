package dev.sealedclient.v26.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sealedclient.common.profile.ProfileBook;
import dev.sealedclient.common.social.FriendBook;
import dev.sealedclient.common.waypoint.WaypointBook;
import dev.sealedclient.v26.PlatformCapabilities26;
import dev.sealedclient.v26.hud.HudLayout26;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudLayoutPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    private ConfigStore26 store() {
        return new ConfigStore26(temporaryDirectory.resolve("sealedclient-26.2.json"));
    }

    @Test
    void draggedPanelPositionsSurviveASaveAndLoad() throws Exception {
        ConfigStore26 store = store();
        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(0.25, 0.75));
        layout.setAnchor(HudLayout26.Panel.ARRAY_LIST, new HudLayout26.Anchor(0.5, 0.125));

        store.save(
                PlatformCapabilities26.createRegistry(),
                new ProfileBook(),
                new FriendBook(),
                new WaypointBook(),
                layout
        );

        HudLayout26 loaded = new HudLayout26();
        assertEquals(
                ConfigStore26.LoadResult.LOADED,
                store.load(
                        PlatformCapabilities26.createRegistry(),
                        new ProfileBook(),
                        new FriendBook(),
                        new WaypointBook(),
                        loaded
                )
        );
        assertEquals(0.25, loaded.anchor(HudLayout26.Panel.INFO).xFraction());
        assertEquals(0.75, loaded.anchor(HudLayout26.Panel.INFO).yFraction());
        assertEquals(0.5, loaded.anchor(HudLayout26.Panel.ARRAY_LIST).xFraction());
        assertEquals(0.125, loaded.anchor(HudLayout26.Panel.ARRAY_LIST).yFraction());
    }

    @Test
    void aConfigWrittenBeforeTheHudEditorStillLoadsWithDefaultPositions() throws Exception {
        ConfigStore26 store = store();
        store.save(
                PlatformCapabilities26.createRegistry(),
                new ProfileBook(),
                new FriendBook(),
                new WaypointBook()
        );

        Path file = store.file();
        JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        root.remove("hudLayout");
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        HudLayout26 loaded = new HudLayout26();
        loaded.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(0.9, 0.9));
        assertEquals(
                ConfigStore26.LoadResult.LOADED,
                store.load(
                        PlatformCapabilities26.createRegistry(),
                        new ProfileBook(),
                        new FriendBook(),
                        new WaypointBook(),
                        loaded
                )
        );
        assertTrue(loaded.isDefault(), "a missing hudLayout resets to defaults, not garbage");
    }

    @Test
    void unknownPanelNamesAndOutOfRangeFractionsAreIgnoredRatherThanRejected() throws Exception {
        ConfigStore26 store = store();
        HudLayout26 layout = new HudLayout26();
        layout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(0.4, 0.4));
        store.save(
                PlatformCapabilities26.createRegistry(),
                new ProfileBook(),
                new FriendBook(),
                new WaypointBook(),
                layout
        );

        Path file = store.file();
        JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        JsonObject hud = root.getAsJsonObject("hudLayout");
        JsonObject future = new JsonObject();
        future.addProperty("x", 0.5);
        future.addProperty("y", 0.5);
        hud.add("PANEL_FROM_A_NEWER_BUILD", future);
        hud.getAsJsonObject("INFO").addProperty("x", 42.0);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        HudLayout26 loaded = new HudLayout26();
        assertEquals(
                ConfigStore26.LoadResult.LOADED,
                store.load(
                        PlatformCapabilities26.createRegistry(),
                        new ProfileBook(),
                        new FriendBook(),
                        new WaypointBook(),
                        loaded
                )
        );
        assertEquals(1.0, loaded.anchor(HudLayout26.Panel.INFO).xFraction(),
                "an out-of-range fraction clamps instead of quarantining the config");
        assertEquals(0.4, loaded.anchor(HudLayout26.Panel.INFO).yFraction());
    }
}
