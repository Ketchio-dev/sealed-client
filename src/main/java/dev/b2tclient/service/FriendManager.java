package dev.b2tclient.service;

import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class FriendManager {
    private final Map<String, Friend> friends = new LinkedHashMap<>();

    public boolean add(String name, UUID uuid) {
        Friend friend = new Friend(name, uuid);
        return friends.put(normalize(name), friend) == null;
    }

    public boolean remove(String name) {
        return friends.remove(normalize(name)) != null;
    }

    public boolean isFriend(Player player) {
        if (player == null) {
            return false;
        }
        Friend byName = friends.get(normalize(player.getGameProfile().getName()));
        if (byName != null) {
            return true;
        }
        UUID uuid = player.getUUID();
        return friends.values().stream()
                .anyMatch(friend -> friend.uuid() != null && friend.uuid().equals(uuid));
    }

    public boolean isFriend(String name) {
        return name != null && friends.containsKey(normalize(name));
    }

    public Optional<Friend> find(String name) {
        return Optional.ofNullable(friends.get(normalize(name)));
    }

    public Collection<Friend> all() {
        return List.copyOf(friends.values());
    }

    public void replaceAll(Collection<Friend> replacements) {
        friends.clear();
        if (replacements != null) {
            replacements.forEach(friend -> add(friend.name(), friend.uuid()));
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "name").trim().toLowerCase(Locale.ROOT);
    }

    public record Friend(String name, UUID uuid) {
        public Friend {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Friend name cannot be blank");
            }
            name = name.trim();
        }
    }
}
