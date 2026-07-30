package dev.b2tclient.e2e;

import dev.b2tclient.B2TClient;
import dev.b2tclient.core.Module;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.concurrent.atomic.AtomicReference;

public final class CommandsAndServicesE2ETest implements FabricClientGameTest {
    private static final String PROFILE = "e2e_services";
    private static final String FRIEND = "E2E_Buddy";
    private static final String WAYPOINT = "E2E_Home";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null);
            boolean expectBaritone = Boolean.getBoolean(
                    "b2t.e2e.expectBaritone"
            );
            if (expectBaritone) {
                exerciseInstalledBaritoneLifecycle(context);
            }
            context.runOnClient(client -> {
                var runtime = B2TClient.runtime();
                var config = runtime.config();
                if (config.activeProfile().equals(PROFILE)) {
                    config.switchProfile(config.DEFAULT_PROFILE, client);
                }
                config.deleteProfile(PROFILE);
                runtime.friends().remove(FRIEND);
                runtime.waypoints().remove(WAYPOINT);

                E2EAssertions.assertTrue(
                        runtime.commands().execute("profile create " + PROFILE, client),
                        "Profile creation command"
                );
                E2EAssertions.assertTrue(
                        runtime.commands().execute("profile use " + PROFILE, client),
                        "Profile switch command"
                );
                E2EAssertions.assertEquals(
                        PROFILE,
                        config.activeProfile(),
                        "Profile must become active"
                );

                boolean allowed = ClientSendMessageEvents.ALLOW_CHAT.invoker()
                        .allowSendChatMessage(";b2t friend add " + FRIEND);
                E2EAssertions.assertFalse(
                        allowed,
                        "Local B2T commands must never be sent to the server"
                );
                E2EAssertions.assertTrue(
                        runtime.friends().isFriend(FRIEND),
                        "Friend command must update the local friend service"
                );

                E2EAssertions.assertTrue(
                        runtime.commands().execute("waypoint add " + WAYPOINT, client),
                        "Waypoint command"
                );
                E2EAssertions.assertTrue(
                        runtime.waypoints().find(WAYPOINT).isPresent(),
                        "Waypoint must be stored"
                );

                E2EAssertions.assertEquals(
                        expectBaritone,
                        runtime.integrations().baritone().available(),
                        expectBaritone
                                ? "Installed Baritone must expose its optional API"
                                : "The isolated E2E client must not bundle optional Baritone"
                );
                E2EAssertions.assertTrue(
                        runtime.commands().execute("baritone status", client),
                        "Baritone status must remain a safe local command"
                );
                E2EAssertions.assertTrue(
                        runtime.commands().execute("diagnostics", client),
                        "Diagnostics must remain a safe local command"
                );
                boolean baritoneAllowed = ClientSendMessageEvents.ALLOW_CHAT.invoker()
                        .allowSendChatMessage(
                                ";b2t baritone goto waypoint " + WAYPOINT
                        );
                E2EAssertions.assertFalse(
                        baritoneAllowed,
                        "Optional Baritone commands must never reach server chat"
                );
                Module autoWalk = runtime.modules().find("auto_walk").orElseThrow();
                autoWalk.setEnabled(true, client);
                E2EAssertions.assertTrue(autoWalk.isEnabled(), "Auto Walk precondition");
                E2EAssertions.assertTrue(
                        runtime.commands().execute("panic", client),
                        "Panic command"
                );
                E2EAssertions.assertFalse(
                        autoWalk.isEnabled(),
                        "Panic must disable movement modules"
                );

                runtime.commands().execute("friend remove " + FRIEND, client);
                runtime.commands().execute("waypoint remove " + WAYPOINT, client);
                config.switchProfile(config.DEFAULT_PROFILE, client);
                E2EAssertions.assertTrue(
                        config.deleteProfile(PROFILE),
                        "Temporary profile cleanup"
                );
                config.save();
            });
        }
    }

    private static void exerciseInstalledBaritoneLifecycle(
            ClientGameTestContext context
    ) {
        context.runOnClient(client -> {
            var navigator = B2TClient.runtime().integrations().baritone();
            var position = client.player.blockPosition();
            var navigation = navigator.goTo(
                    position.getX() + 2,
                    position.getY(),
                    position.getZ()
            );
            E2EAssertions.assertTrue(
                    navigation.success(),
                    "Installed Baritone must accept a nonzero local path"
            );
            E2EAssertions.assertTrue(
                    navigator.status().ownedByB2T(),
                    "A started local goal must be owned by B2T"
            );
            E2EAssertions.assertTrue(
                    navigator.pause().success(),
                    "B2T must pause its own Baritone path"
            );
            E2EAssertions.assertEquals(
                    dev.b2tclient.integration.BaritoneNavigator.NavigationState.PAUSED,
                    navigator.status().state(),
                    "Paused navigation must expose its lifecycle state"
            );
            E2EAssertions.assertTrue(
                    navigator.resume().success(),
                    "B2T must resume its paused Baritone path"
            );
        });

        AtomicReference<dev.b2tclient.integration.BaritoneNavigator.NavigationStatus>
                lastStatus = new AtomicReference<>();
        try {
            context.waitFor(client -> {
                var status = B2TClient.runtime()
                    .integrations()
                    .baritone()
                    .status();
                lastStatus.set(status);
                var state = status.state();
                return state
                    == dev.b2tclient.integration.BaritoneNavigator.NavigationState.COMPLETED
                    || state
                    == dev.b2tclient.integration.BaritoneNavigator.NavigationState.FAILED
                    || state
                    == dev.b2tclient.integration.BaritoneNavigator.NavigationState.ERROR
                    || state
                    == dev.b2tclient.integration.BaritoneNavigator.NavigationState.CANCELLED;
            }, 400);
        } catch (AssertionError timeout) {
            throw new AssertionError(
                    "Installed Baritone lifecycle timed out at " + lastStatus.get(),
                    timeout
            );
        }

        context.runOnClient(client -> {
            var navigator = B2TClient.runtime().integrations().baritone();
            E2EAssertions.assertEquals(
                    dev.b2tclient.integration.BaritoneNavigator.NavigationState.COMPLETED,
                    navigator.status().state(),
                    "Installed Baritone must complete the nonzero short path"
            );

            var position = client.player.blockPosition();
            E2EAssertions.assertTrue(
                    navigator.goTo(
                            position.getX() + 2,
                            position.getY(),
                            position.getZ()
                    ).success(),
                    "A second owned goal must start after completion"
            );
            E2EAssertions.assertTrue(
                    navigator.stop().success(),
                    "B2T must cancel its own active Baritone goal"
            );
            E2EAssertions.assertEquals(
                    dev.b2tclient.integration.BaritoneNavigator.NavigationState.CANCELLED,
                    navigator.status().state(),
                    "Explicit stop must expose a canceled lifecycle state"
            );
            E2EAssertions.assertTrue(
                    navigator.status().detail().contains("stopped"),
                    "Explicit stop must retain its own cancellation diagnostic"
            );
        });
    }
}
