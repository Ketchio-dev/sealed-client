package dev.sealedclient.common.arbitration;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Computes which utility channels are already committed elsewhere.
 *
 * <p>Combat, movement and utility each arbitrate their own channels, so nothing
 * inside a single arbiter can tell that another subsystem already committed the
 * shared hardware: there is one hotbar, one inventory, and one head. Utility
 * runs last and therefore has to be told what the other two already took, which
 * this class computes.</p>
 *
 * <p>The rules used to be a hand-written chain of {@code if} statements in the
 * platform runtime, where adding a channel meant remembering to add a matching
 * branch. Expressing them as a pure set operation makes the mapping explicit
 * and lets it be tested without a running client.</p>
 *
 * <p>Channel enums differ per subsystem, so the caller supplies the names it
 * cares about; matching is by enum constant name.</p>
 */
public final class CrossArbiterReservations {
    private CrossArbiterReservations() {
    }

    /**
     * @param utilityChannels    every channel the utility arbiter knows
     * @param combatGranted      channels currently granted by combat arbitration
     * @param movementGranted    channels currently granted by movement arbitration
     * @param sharedChannelNames names that mean the same hardware in every subsystem
     * @param reserveEverything  {@code true} when an external controller such as
     *                           Baritone owns the player outright
     * @return the utility channels that must be treated as already taken
     */
    public static <U extends Enum<U>> Set<U> compute(
            Class<U> utilityChannels,
            Set<? extends Enum<?>> combatGranted,
            Set<? extends Enum<?>> movementGranted,
            Set<String> sharedChannelNames,
            boolean reserveEverything
    ) {
        Objects.requireNonNull(utilityChannels, "utilityChannels");
        Objects.requireNonNull(combatGranted, "combatGranted");
        Objects.requireNonNull(movementGranted, "movementGranted");
        Objects.requireNonNull(sharedChannelNames, "sharedChannelNames");

        if (reserveEverything) {
            return EnumSet.allOf(utilityChannels);
        }

        Set<String> takenNames = new LinkedHashSet<>();
        collectNames(combatGranted, sharedChannelNames, takenNames);
        collectNames(movementGranted, sharedChannelNames, takenNames);

        EnumSet<U> reserved = EnumSet.noneOf(utilityChannels);
        for (U channel : utilityChannels.getEnumConstants()) {
            if (takenNames.contains(channel.name())) {
                reserved.add(channel);
            }
        }
        return reserved;
    }

    private static void collectNames(
            Set<? extends Enum<?>> granted,
            Set<String> sharedChannelNames,
            Set<String> target
    ) {
        for (Enum<?> channel : granted) {
            if (channel != null && sharedChannelNames.contains(channel.name())) {
                target.add(channel.name());
            }
        }
    }
}
