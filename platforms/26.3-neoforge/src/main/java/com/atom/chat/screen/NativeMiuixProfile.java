package com.atom.chat.screen;

import com.atom.chat.avatar.AvatarStore;
import com.atom.chat.banner.BannerStore;
import com.atom.chat.chat.BlockList;
import com.atom.chat.chat.PlayerRef;
import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.miuix.api.MiuixHost;
import com.atom.chat.profile.ProfileField;
import com.atom.chat.profile.ProfileRecord;
import com.atom.chat.profile.ProfileSync;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Builds the Miuix profile model from the live client and profile stores. */
public final class NativeMiuixProfile {
    private static final long STATS_CACHE_MS = 1_000L;

    private static UUID cachedStatsPlayer;
    private static long cachedStatsAt;
    private static StatTotals cachedStats;

    private NativeMiuixProfile() {
    }

    public static MiuixHost.Profile snapshot(Minecraft client, PlayerRef subject,
                                             AvatarStore avatarStore) {
        if (client == null) {
            return new MiuixHost.Profile(null, false, "", null, false, false,
                    false, false, List.of(), List.of());
        }

        UUID ownUuid = client.player == null ? null : client.player.getUUID();
        UUID uuid = subject == null
                ? ownUuid
                : subject.uuid();
        String name = subject == null
                ? (client.player == null ? null : client.player.getName().getString())
                : subject.realName();

        PlayerInfo onlineInfo = findOnlinePlayer(client, uuid, name);
        boolean online = onlineInfo != null || (subject == null && client.player != null);
        if (onlineInfo != null) {
            uuid = onlineInfo.getProfile().id();
            name = onlineInfo.getProfile().name();
        }
        boolean self = subject == null || (ownUuid != null && ownUuid.equals(uuid));
        if (self && client.player != null) {
            uuid = ownUuid;
            name = client.player.getName().getString();
        }

        String cleanName = name == null || name.isBlank() ? null : name;
        String uuidText = uuid == null ? null : uuid.toString();
        String playerKey = cleanName != null
                ? PlayerRef.of(uuid, cleanName).key()
                : (uuidText == null ? "" : "u:" + uuidText);
        boolean blocked = cleanName != null && BlockList.isBlocked(cleanName);
        String avatarKey = uuidText == null ? null : "avatar:" + uuidText;
        String hatKey = uuidText == null ? null : "hat:" + uuidText;

        MiuixHost.Player player = new MiuixHost.Player(
                playerKey, uuidText, cleanName, cleanName, online, blocked, avatarKey, hatKey);

        ProfileRecord synchronizedProfile = uuid == null ? null : ProfileSync.record(uuid);
        boolean remoteAvatar = hasImage(synchronizedProfile, ProfileField.AVATAR);
        boolean remoteBanner = hasImage(synchronizedProfile, ProfileField.BANNER);
        AtomChatConfig config = AtomChatConfig.get();
        boolean customAvatar = self
                ? (avatarStore != null && avatarStore.isSet())
                    || ProfileSync.hasOwnImage(ProfileField.AVATAR)
                : remoteAvatar;
        boolean customBanner = self
                ? config.customBannerEnabled
                    && ((BannerStore.isSet()) || ProfileSync.hasOwnImage(ProfileField.BANNER))
                : remoteBanner;
        String bannerKey = customBanner && uuidText != null ? "banner:" + uuidText : null;
        String signature = self
                ? (config.playerSignature == null ? "" : config.playerSignature)
                : synchronizedProfile == null ? "" : safe(synchronizedProfile.signature());

        Boolean operator = null;
        if (self && client.player != null) {
            operator = client.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        } else if (uuid != null) {
            operator = ProfileSync.operator(uuid);
        }

        List<MiuixHost.Fact> statistics = new ArrayList<>(3);
        String ping = tr("atomchat.profile.ping.value", onlineInfo == null ? -1 : onlineInfo.getLatency());
        statistics.add(fact("atomchat.profile.ping", ping, ping));
        if (self) {
            long sessionStartedAt = com.atom.chat.page.ProfilePage.sessionStartedAt();
            if (sessionStartedAt > 0L) {
                long seconds = Math.max(0L, (System.currentTimeMillis() - sessionStartedAt) / 1_000L);
                long hours = seconds / 3_600L;
                long minutes = (seconds % 3_600L) / 60L;
                long remainingSeconds = seconds % 60L;
                String value = hours > 0L
                        ? tr("atomchat.profile.session.h", hours, minutes)
                        : minutes > 0L
                            ? tr("atomchat.profile.session.m", minutes, remainingSeconds)
                            : tr("atomchat.profile.session.s", remainingSeconds);
                statistics.add(fact("atomchat.profile.session", value, value));
            }
            if (client.player != null && uuid != null) {
                StatTotals totals = statTotals(client, uuid);
                String distance = distance(totals.walkCm());
                String value = tr("atomchat.profile.stats.value", totals.mined(), totals.killed(), distance);
                statistics.add(fact("atomchat.profile.stats",
                        tr("atomchat.profile.stats.mined", totals.mined()), value));
            }
        }
        if (!self || client.player == null || uuid == null) {
            statistics.add(fact("atomchat.profile.stats", "-", "-"));
        }

        List<MiuixHost.Fact> facts = new ArrayList<>(4);
        if (uuidText != null) {
            facts.add(fact("atomchat.profile.uuid", uuidText, uuidText));
        }
        if (operator != null) {
            String value = tr(operator ? "atomchat.profile.role.op" : "atomchat.profile.role.member");
            facts.add(fact("atomchat.profile.role", value, value));
        }
        String server = currentServer(client);
        facts.add(fact("atomchat.profile.server", server == null ? "-" : server, ""));

        return new MiuixHost.Profile(player, self, signature, bannerKey,
                customAvatar, customBanner, self && ProfileSync.syncing(),
                self && ProfileSync.unsynced(), List.copyOf(statistics), List.copyOf(facts));
    }

    private static PlayerInfo findOnlinePlayer(Minecraft client, UUID uuid, String name) {
        if (client.getConnection() == null) {
            return null;
        }
        if (uuid != null) {
            PlayerInfo byUuid = client.getConnection().getPlayerInfo(uuid);
            if (byUuid != null) {
                return byUuid;
            }
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            if (name.equalsIgnoreCase(info.getProfile().name())) {
                return info;
            }
        }
        return null;
    }

    private static String currentServer(Minecraft client) {
        if (client.getCurrentServer() != null) {
            String address = client.getCurrentServer().ip;
            if (address != null && !address.isBlank()) {
                return address;
            }
        }
        return client.getSingleplayerServer() == null
                ? null
                : tr("atomchat.profile.server.singleplayer");
    }

    private static boolean hasImage(ProfileRecord profile, ProfileField field) {
        if (profile == null) {
            return false;
        }
        String reference = field == ProfileField.AVATAR ? profile.avatar() : profile.banner();
        return reference != null && !reference.isEmpty();
    }

    private static MiuixHost.Fact fact(String labelKey, String value, String copy) {
        return new MiuixHost.Fact(tr(labelKey), value, copy);
    }

    private static String tr(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static StatTotals statTotals(Minecraft client, UUID playerUuid) {
        long now = System.currentTimeMillis();
        if (playerUuid.equals(cachedStatsPlayer) && cachedStats != null
                && now - cachedStatsAt < STATS_CACHE_MS) {
            return cachedStats;
        }
        StatsCounter stats = client.player.getStats();
        long mined = 0L;
        long killed = 0L;
        for (Object2IntMap.Entry<Stat<?>> entry : stats.stats.object2IntEntrySet()) {
            Stat<?> stat = entry.getKey();
            if (stat.getType() == Stats.BLOCK_MINED) {
                mined += entry.getIntValue();
            } else if (stat.getType() == Stats.ENTITY_KILLED) {
                killed += entry.getIntValue();
            }
        }
        long walkCm = stats.getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM));
        cachedStatsPlayer = playerUuid;
        cachedStatsAt = now;
        cachedStats = new StatTotals(mined, killed, walkCm);
        return cachedStats;
    }

    private static String distance(long centimeters) {
        if (centimeters >= 100_000L) {
            return String.format(Locale.ROOT, "%.1f km", centimeters / 100_000.0);
        }
        return (centimeters / 100L) + " m";
    }

    private record StatTotals(long mined, long killed, long walkCm) {
    }
}
