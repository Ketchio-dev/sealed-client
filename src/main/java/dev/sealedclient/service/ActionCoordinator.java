package dev.sealedclient.service;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ActionCoordinator {
    private final Map<Channel, Claim> claims = new EnumMap<>(Channel.class);
    private final Map<KeyMapping, String> controlledKeys = new HashMap<>();
    private long tick;

    public void beginTick(Minecraft minecraft) {
        tick++;
        claims.entrySet().removeIf(entry -> entry.getValue().expiresAt() < tick);
        if (minecraft == null || minecraft.player == null) {
            releaseAll(minecraft);
        }
    }

    public boolean claim(Channel channel, String owner, int priority, int durationTicks) {
        Objects.requireNonNull(channel, "channel");
        String requestedOwner = requireOwner(owner);
        Claim existing = claims.get(channel);
        if (existing != null
                && !existing.owner().equals(requestedOwner)
                && existing.priority() > priority
                && existing.expiresAt() >= tick) {
            return false;
        }
        claims.put(channel, new Claim(
                requestedOwner,
                priority,
                tick + Math.max(0, durationTicks)
        ));
        return true;
    }

    public boolean owns(Channel channel, String owner) {
        Claim claim = claims.get(channel);
        return claim != null && claim.expiresAt() >= tick && claim.owner().equals(owner);
    }

    public boolean setKey(
            Minecraft minecraft,
            Channel channel,
            String owner,
            int priority,
            KeyMapping key,
            boolean down
    ) {
        if (!claim(channel, owner, priority, 1)) {
            return false;
        }
        key.setDown(down);
        if (down) {
            controlledKeys.put(key, owner);
        } else {
            controlledKeys.remove(key);
        }
        return true;
    }

    public void releaseOwner(Minecraft minecraft, String owner) {
        String requestedOwner = requireOwner(owner);
        claims.entrySet().removeIf(entry -> entry.getValue().owner().equals(requestedOwner));
        controlledKeys.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(requestedOwner)) {
                return false;
            }
            entry.getKey().setDown(false);
            return true;
        });
    }

    public void releaseAll(Minecraft minecraft) {
        controlledKeys.keySet().forEach(key -> key.setDown(false));
        controlledKeys.clear();
        claims.clear();
    }

    public long tick() {
        return tick;
    }

    public DiagnosticSnapshot diagnostics() {
        Map<Channel, String> owners = new EnumMap<>(Channel.class);
        for (Map.Entry<Channel, Claim> entry : claims.entrySet()) {
            if (entry.getValue().expiresAt() >= tick) {
                owners.put(entry.getKey(), entry.getValue().owner());
            }
        }
        return new DiagnosticSnapshot(tick, Map.copyOf(owners), controlledKeys.size());
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Action owner cannot be blank");
        }
        return owner;
    }

    private record Claim(String owner, int priority, long expiresAt) {
    }

    public record DiagnosticSnapshot(
            long tick,
            Map<Channel, String> channelOwners,
            int controlledKeyCount
    ) {
        public DiagnosticSnapshot {
            channelOwners = Map.copyOf(channelOwners);
            if (controlledKeyCount < 0) {
                throw new IllegalArgumentException("controlledKeyCount cannot be negative");
            }
        }
    }

    public enum Channel {
        MOVEMENT,
        ROTATION,
        HOTBAR,
        INVENTORY,
        ATTACK,
        USE,
        NETWORK
    }
}
