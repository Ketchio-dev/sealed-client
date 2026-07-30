package dev.sealedclient.event;

import net.minecraft.client.Minecraft;

import java.util.Objects;

public record ClientTickEvent(Minecraft minecraft, Phase phase, long tick) {
    public ClientTickEvent {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(phase, "phase");
    }

    public enum Phase {
        PRE,
        POST
    }
}
