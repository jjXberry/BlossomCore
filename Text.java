package com.blossomsmp.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Colours and number formatting. Understands &a style colours and &#FF69B4 hex colours. */
public final class Text {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private Text() {
    }

    /** Text with colours, for chat, titles, item names and lore (never italic unless you write &o). */
    public static Component c(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return AMPERSAND.deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> c(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(c(line));
        }
        return out;
    }

    /** The same text in the old "section sign" format, for PlaceholderAPI (TAB, holograms). */
    public static String section(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return SECTION.serialize(AMPERSAND.deserialize(text));
    }

    /** Swaps {key} for value: fill("Hi {player}", "{player}", "Steve"). */
    public static String fill(String text, String... replacements) {
        if (text == null) {
            return "";
        }
        String out = text;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            out = out.replace(replacements[i], replacements[i + 1] == null ? "" : replacements[i + 1]);
        }
        return out;
    }

    /** 12500 -> "12,500" */
    public static String number(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** 1.5 -> "1.5", 2.0 -> "2" */
    public static String decimal(double value) {
        if (value == Math.rint(value)) {
            return number((long) value);
        }
        return String.format(Locale.US, "%,.1f", value);
    }

    /** "IRON_SWORD" -> "Iron Sword" */
    public static String pretty(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String word : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /** 1 -> "I", 4 -> "IV" (falls back to the number above 10). */
    public static String roman(int level) {
        String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return level >= 1 && level <= 10 ? numerals[level] : String.valueOf(level);
    }
}
