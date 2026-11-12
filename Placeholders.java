package com.blossomsmp.core.hook;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.DataStore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.pvp.PvpManager;
import com.blossomsmp.core.rank.Rank;
import com.blossomsmp.core.util.Text;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Only loaded when PlaceholderAPI is installed.
 *
 *   %blossomcore_rank%            Bloom (with colours)        %blossomcore_rank_prefix%   the short prefix
 *   %blossomcore_next_rank%       Blossom                     %blossomcore_points%        1,250
 *   %blossomcore_pvp_rank%        Warrior (with colours)      %blossomcore_pvp_rating%    1284
 *   %blossomcore_pvp_kills%  %blossomcore_pvp_deaths%  %blossomcore_pvp_kdr%  %blossomcore_pvp_streak%
 *   %blossomcore_pvp_best_streak%  %blossomcore_pvp_position%  %blossomcore_season%
 *   %blossomcore_top_1_name%  %blossomcore_top_1_rating%  %blossomcore_top_1_kills%   (1 to 10)
 */
public final class Placeholders extends PlaceholderExpansion {

    private final BlossomCore plugin;

    public Placeholders(BlossomCore plugin) {
        this.plugin = plugin;
    }

    /** Asks another plugin's placeholder, for example "%blossomteams_name%". */
    public static String ask(Player player, String placeholder) {
        return PlaceholderAPI.setPlaceholders(player, placeholder);
    }

    @Override
    public String getIdentifier() {
        return "blossomcore";
    }

    @Override
    public String getAuthor() {
        return "BlossomSMP";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);

        if (key.equals("season")) {
            return String.valueOf(plugin.pvp().season());
        }
        if (key.startsWith("top_")) {
            String[] parts = key.split("_", 3);
            if (parts.length < 3) {
                return null;
            }
            int place;
            try {
                place = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            List<DataStore.TopEntry> top = plugin.store().top(10);
            if (place < 1 || place > top.size()) {
                return parts[2].equals("name") ? "-" : "0";
            }
            DataStore.TopEntry entry = top.get(place - 1);
            return switch (parts[2]) {
                case "name" -> entry.name();
                case "rating" -> String.valueOf(entry.rating());
                case "kills" -> String.valueOf(entry.kills());
                case "rank" -> Text.section(plugin.pvp().tierOf(entry.rating()).name());
                default -> null;
            };
        }

        if (player == null) {
            return "";
        }
        PlayerData data = plugin.store().getIfLoaded(player.getUniqueId());
        if (data == null) {
            return "";
        }
        Rank rank = plugin.ranks().of(data);
        PvpManager pvp = plugin.pvp();
        switch (key) {
            case "rank":
                return Text.section(rank.name());
            case "rank_prefix":
                return Text.section(rank.prefix());
            case "rank_id":
                return rank.id();
            case "next_rank": {
                Rank next = plugin.ranks().next(rank);
                return Text.section(next == null ? plugin.raw("max-rank") : next.name());
            }
            case "points":
                return Text.number(data.points);
            case "pvp_rank":
                return Text.section(pvp.tierOf(data.rating).name());
            case "pvp_rating":
                return String.valueOf(data.rating);
            case "pvp_kills":
                return String.valueOf(data.kills);
            case "pvp_deaths":
                return String.valueOf(data.deaths);
            case "pvp_kdr":
                return PvpManager.kdr(data);
            case "pvp_streak":
                return String.valueOf(data.streak);
            case "pvp_best_streak":
                return String.valueOf(data.bestStreak);
            case "pvp_position": {
                int position = plugin.store().position(data.uuid);
                return position == 0 ? "-" : String.valueOf(position);
            }
            default:
                return null;
        }
    }
}
