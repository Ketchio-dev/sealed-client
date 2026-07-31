package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.platform.EntityAccess;
import dev.sealedclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Detaches the local camera from the player without moving the server-side
 * player. Mouse rotation is forwarded by the corresponding camera mixin.
 */
public final class FreecamModule extends Module implements TickableModule {
    public static final String ID = "freecam";
    private static final int MOVEMENT_PRIORITY = 100;

    private static FreecamModule active;

    private final ActionCoordinator actions;
    private final DoubleSetting speed = addSetting(new DoubleSetting(
            "speed",
            "Speed",
            "Free-camera movement speed in blocks per client tick.",
            0.50,
            0.05,
            5.00,
            0.05
    ));
    private final DoubleSetting sprintMultiplier = addSetting(new DoubleSetting(
            "sprint_multiplier",
            "Sprint multiplier",
            "Movement-speed multiplier while the sprint key is held.",
            2.00,
            1.00,
            5.00,
            0.25
    ));

    private Marker camera;
    private Entity previousCamera;
    private LocalPlayer capturedPlayer;
    private ClientInput previousInput;
    private ClientInput suppressedInput;

    public FreecamModule(ActionCoordinator actions) {
        super(
                ID,
                "Freecam",
                "Detaches a no-clip camera while leaving the real player stationary.",
                Category.VISUAL,
                false,
                ModuleRisk.MOVEMENT
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    protected void onEnable(Minecraft minecraft) {
        if (active != null && active != this) {
            active.releaseState(minecraft);
        }
        active = this;

        try {
            attachIfPossible(minecraft);
        } catch (RuntimeException exception) {
            releaseState(minecraft);
            active = null;
            throw exception;
        }
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            releaseState(minecraft);
            return;
        }

        if (capturedPlayer != minecraft.player
                || camera == null
                || camera.level() != minecraft.level
                || capturedPlayer.input != suppressedInput) {
            releaseState(minecraft);
            attachIfPossible(minecraft);
        }

        if (camera == null) {
            return;
        }

        if (!actions.claim(
                ActionCoordinator.Channel.MOVEMENT,
                ID,
                MOVEMENT_PRIORITY,
                2
        )) {
            releaseState(minecraft);
            return;
        }

        if (minecraft.screen != null) {
            return;
        }

        if (minecraft.getCameraEntity() != camera) {
            minecraft.setCameraEntity(camera);
        }

        moveCamera(minecraft);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        releaseState(minecraft);
        if (active == this) {
            active = null;
        }
    }

    /**
     * Called by the mouse mixin. Returns true when the normal player turn was
     * consumed and applied to the detached camera instead.
     */
    public static boolean redirectMouseTurn(
            LocalPlayer player,
            double horizontal,
            double vertical
    ) {
        FreecamModule module = active;
        if (module == null
                || !module.isEnabled()
                || module.camera == null
                || module.capturedPlayer != player) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getCameraEntity() != module.camera) {
            return false;
        }

        module.camera.turn(horizontal, vertical);
        return true;
    }

    private void attachIfPossible(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || camera != null) {
            return;
        }
        if (!actions.claim(
                ActionCoordinator.Channel.MOVEMENT,
                ID,
                MOVEMENT_PRIORITY,
                2
        )) {
            return;
        }

        LocalPlayer player = minecraft.player;
        Entity source = minecraft.getCameraEntity();
        if (source == null || source.level() != minecraft.level) {
            source = player;
        }

        Marker nextCamera = new Marker(EntityType.MARKER, minecraft.level);
        Vec3 start = source.getEyePosition();
        nextCamera.noPhysics = true;
        nextCamera.setNoGravity(true);
        nextCamera.setSilent(true);
        EntityAccess.snapTo(
                nextCamera,
                start.x,
                start.y,
                start.z,
                source.getYRot(),
                source.getXRot()
        );
        nextCamera.setOldPosAndRot(start, source.getYRot(), source.getXRot());

        ClientInput inertInput = new ClientInput();
        previousCamera = source;
        capturedPlayer = player;
        previousInput = player.input;
        suppressedInput = inertInput;
        camera = nextCamera;

        player.input = inertInput;
        minecraft.setCameraEntity(nextCamera);
    }

    private void moveCamera(Minecraft minecraft) {
        double forward = axis(
                minecraft.options.keyUp.isDown(),
                minecraft.options.keyDown.isDown()
        );
        double strafe = axis(
                minecraft.options.keyRight.isDown(),
                minecraft.options.keyLeft.isDown()
        );
        double vertical = axis(
                minecraft.options.keyJump.isDown(),
                minecraft.options.keyShift.isDown()
        );

        double horizontalLength = Math.hypot(forward, strafe);
        if (horizontalLength > 1.0) {
            forward /= horizontalLength;
            strafe /= horizontalLength;
        }

        double movementSpeed = speed.get();
        if (minecraft.options.keySprint.isDown()) {
            movementSpeed *= sprintMultiplier.get();
        }

        double yaw = Math.toRadians(camera.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        Vec3 movement = new Vec3(
                strafe * cos - forward * sin,
                vertical,
                forward * cos + strafe * sin
        );
        if (movement.lengthSqr() > 1.0) {
            movement = movement.normalize();
        }
        movement = movement.scale(movementSpeed);

        camera.setOldPosAndRot();
        camera.setPos(camera.position().add(movement));
        camera.setDeltaMovement(Vec3.ZERO);
    }

    private void releaseState(Minecraft minecraft) {
        actions.releaseOwner(minecraft, ID);

        Marker detachedCamera = camera;
        LocalPlayer player = capturedPlayer;
        ClientInput oldInput = previousInput;
        ClientInput inertInput = suppressedInput;
        Entity oldCamera = previousCamera;

        camera = null;
        capturedPlayer = null;
        previousInput = null;
        suppressedInput = null;
        previousCamera = null;

        if (player != null && player.input == inertInput && oldInput != null) {
            player.input = oldInput;
        }

        if (detachedCamera != null && minecraft.getCameraEntity() == detachedCamera) {
            Entity restore = null;
            if (minecraft.player != null && minecraft.level != null) {
                restore = oldCamera != null && oldCamera.level() == minecraft.level
                        ? oldCamera
                        : minecraft.player;
            }
            minecraft.setCameraEntity(restore);
        }
    }

    private static double axis(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0;
        }
        return positive ? 1.0 : -1.0;
    }
}
