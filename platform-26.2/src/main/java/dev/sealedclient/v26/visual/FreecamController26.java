package dev.sealedclient.v26.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Owns the complete lifecycle of the detached 26.2 free camera.
 *
 * <p>The real player's {@link ClientInput} is replaced with an inert instance
 * while attached, so movement packets continue to describe a stationary
 * player. The mouse mixin calls {@link #redirectMouseTurn(LocalPlayer, double,
 * double)} to rotate only the detached camera.</p>
 */
public final class FreecamController26 {
    private static volatile FreecamController26 activeController;

    private Configuration configuration = Configuration.DEFAULT;
    private Marker camera;
    private Entity previousCamera;
    private LocalPlayer capturedPlayer;
    private ClientInput previousInput;
    private ClientInput suppressedInput;

    public Configuration configuration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    /**
     * Advances Freecam by one client tick.
     *
     * @param enabled module toggle; callers should pass false by default
     * @param allowed cross-module arbitration/safety result
     */
    public void tick(Minecraft client, boolean enabled, boolean allowed) {
        if (!enabled || !allowed || !sessionReady(client)) {
            release(client);
            return;
        }

        if (capturedPlayer != client.player
                || camera == null
                || camera.level() != client.level
                || capturedPlayer.input != suppressedInput) {
            release(client);
            if (!attach(client)) {
                return;
            }
        }

        if (client.getCameraEntity() != camera) {
            client.setCameraEntity(camera);
        }
        if (client.gui.screen() == null) {
            moveCamera(client);
        }
    }

    /**
     * Restores both the original input object and camera entity. Safe to call
     * repeatedly during disconnect, respawn, dimension change, or shutdown.
     */
    public void release(Minecraft client) {
        Marker detached = camera;
        Entity restoreCamera = previousCamera;
        LocalPlayer player = capturedPlayer;
        ClientInput restoreInput = previousInput;
        ClientInput inertInput = suppressedInput;

        camera = null;
        previousCamera = null;
        capturedPlayer = null;
        previousInput = null;
        suppressedInput = null;
        if (activeController == this) {
            activeController = null;
        }

        if (player != null
                && player.input == inertInput
                && restoreInput != null) {
            player.input = restoreInput;
        }
        if (client == null
                || detached == null
                || client.getCameraEntity() != detached) {
            return;
        }

        Entity fallback = null;
        if (client.level != null && client.player != null) {
            fallback = restoreCamera != null
                    && restoreCamera.level() == client.level
                    ? restoreCamera
                    : client.player;
        }
        client.setCameraEntity(fallback);
    }

    public boolean attached() {
        return camera != null
                && capturedPlayer != null
                && capturedPlayer.input == suppressedInput;
    }

    /**
     * Central movement arbitration gate. While true, no other module should
     * claim movement keys or replace the player's inert input instance.
     */
    public boolean ownsMovement() {
        return activeController == this && attached();
    }

    public static boolean active() {
        FreecamController26 controller = activeController;
        return controller != null && controller.ownsMovement();
    }

    public Vec3 cameraPosition() {
        return camera == null ? null : camera.position();
    }

    /**
     * The attached camera's position, or null when no camera is detached.
     * Lets world scans centre on what is actually being rendered.
     */
    public static Vec3 activeCameraPosition() {
        FreecamController26 controller = activeController;
        return controller == null ? null : controller.cameraPosition();
    }

    /**
     * Called only by the Freecam mouse redirect. Returning false preserves the
     * vanilla player turn.
     */
    public static boolean redirectMouseTurn(
            LocalPlayer player,
            double horizontal,
            double vertical
    ) {
        FreecamController26 controller = activeController;
        if (controller == null
                || controller.camera == null
                || controller.capturedPlayer != player
                || !Double.isFinite(horizontal)
                || !Double.isFinite(vertical)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null
                || client.getCameraEntity() != controller.camera
                || player.input != controller.suppressedInput) {
            return false;
        }
        controller.camera.turn(horizontal, vertical);
        return true;
    }

    /**
     * Pure movement calculation used by both live code and deterministic
     * tests. Diagonal and vertical combinations are capped to the requested
     * speed.
     */
    public static Vec3 movementVector(
            boolean forwardPressed,
            boolean backwardPressed,
            boolean rightPressed,
            boolean leftPressed,
            boolean jumpPressed,
            boolean descendPressed,
            double yawDegrees,
            double speed
    ) {
        if (!Double.isFinite(yawDegrees)
                || !Double.isFinite(speed)
                || speed < 0.0) {
            return Vec3.ZERO;
        }
        double forward = axis(forwardPressed, backwardPressed);
        double strafe = axis(rightPressed, leftPressed);
        double vertical = axis(jumpPressed, descendPressed);

        double horizontalLength = Math.hypot(forward, strafe);
        if (horizontalLength > 1.0) {
            forward /= horizontalLength;
            strafe /= horizontalLength;
        }
        double yaw = Math.toRadians(yawDegrees);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        Vec3 direction = new Vec3(
                strafe * cos - forward * sin,
                vertical,
                forward * cos + strafe * sin
        );
        if (direction.lengthSqr() > 1.0) {
            direction = direction.normalize();
        }
        return direction.scale(speed);
    }

    private boolean attach(Minecraft client) {
        if (!sessionReady(client) || camera != null) {
            return false;
        }
        FreecamController26 previousOwner = activeController;
        if (previousOwner != null && previousOwner != this) {
            previousOwner.release(client);
        }

        LocalPlayer player = client.player;
        Entity source = client.getCameraEntity();
        if (source == null || source.level() != client.level) {
            source = player;
        }
        Vec3 start = source.getEyePosition();
        Marker detached = new Marker(EntityTypes.MARKER, client.level);
        detached.noPhysics = true;
        detached.setNoGravity(true);
        detached.setSilent(true);
        detached.snapTo(
                start.x,
                start.y,
                start.z,
                source.getYRot(),
                source.getXRot()
        );
        detached.setOldPosAndRot(start, source.getYRot(), source.getXRot());
        detached.setDeltaMovement(Vec3.ZERO);

        ClientInput inert = new ClientInput();
        previousCamera = source;
        capturedPlayer = player;
        previousInput = player.input;
        suppressedInput = inert;
        camera = detached;
        activeController = this;
        player.input = inert;
        client.setCameraEntity(detached);
        return true;
    }

    private void moveCamera(Minecraft client) {
        double movementSpeed = configuration.speed();
        if (client.options.keySprint.isDown()) {
            movementSpeed *= configuration.sprintMultiplier();
        }
        Vec3 movement = movementVector(
                client.options.keyUp.isDown(),
                client.options.keyDown.isDown(),
                client.options.keyRight.isDown(),
                client.options.keyLeft.isDown(),
                client.options.keyJump.isDown(),
                client.options.keyShift.isDown(),
                camera.getYRot(),
                movementSpeed
        );
        camera.setOldPosAndRot();
        camera.setPos(camera.position().add(movement));
        camera.setDeltaMovement(Vec3.ZERO);
    }

    private static boolean sessionReady(Minecraft client) {
        if (client == null
                || client.level == null
                || client.player == null
                || client.getConnection() == null
                || client.getConnection().getConnection() == null
                || !client.getConnection().getConnection().isConnected()) {
            return false;
        }
        return client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator();
    }

    private static double axis(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0;
        }
        return positive ? 1.0 : -1.0;
    }

    public record Configuration(double speed, double sprintMultiplier) {
        public static final Configuration DEFAULT =
                new Configuration(0.50, 2.00);

        public Configuration {
            if (!Double.isFinite(speed) || speed < 0.05 || speed > 5.0) {
                throw new IllegalArgumentException(
                        "speed must be finite and in [0.05, 5.0]"
                );
            }
            if (!Double.isFinite(sprintMultiplier)
                    || sprintMultiplier < 1.0
                    || sprintMultiplier > 5.0) {
                throw new IllegalArgumentException(
                        "sprintMultiplier must be finite and in [1.0, 5.0]"
                );
            }
        }
    }
}
