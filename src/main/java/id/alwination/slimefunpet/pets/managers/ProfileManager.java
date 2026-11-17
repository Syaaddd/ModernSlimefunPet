package id.alwination.slimefunpet.pets.managers;

import id.alwination.slimefunpet.pets.PlayerProfile;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * Every player profile, loaded at startup. A player without one gets a fresh profile the first
 * time anything asks; it is saved once it is changed (the callers queue it).
 */
public final class ProfileManager {

    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    public PlayerProfile getProfile(UUID owner) {
        return this.profiles.computeIfAbsent(owner, PlayerProfile::new);
    }

    public PlayerProfile getProfile(OfflinePlayer player) {
        return this.getProfile(player.getUniqueId());
    }

    /** The profile only when it exists, without creating one. */
    public @Nullable PlayerProfile findProfile(UUID owner) {
        return this.profiles.get(owner);
    }

    public Collection<PlayerProfile> getProfiles() {
        return this.profiles.values();
    }

    public void load(Collection<PlayerProfile> loaded) {
        this.profiles.clear();
        for (PlayerProfile profile : loaded) {
            this.profiles.put(profile.getOwner(), profile);
        }
    }
}
