package dev.sealedclient.common.social;

import java.util.Optional;
import java.util.UUID;

public record FriendEntry(String name, UUID uuid) {
    public FriendEntry {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Friend name must not be blank");
        }
        name = name.trim();
    }

    public Optional<UUID> optionalUuid() {
        return Optional.ofNullable(uuid);
    }

    public String displayName() {
        return name;
    }
}
