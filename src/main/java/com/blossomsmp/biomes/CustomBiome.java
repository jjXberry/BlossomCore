package com.blossomsmp.biomes;

/** The six Blossom SMP biomes. The id must match the file name in the datapack (blossom:&lt;id&gt;). */
public enum CustomBiome {
    SAKURA_FOREST("sakura_forest"),
    BLOSSOM_MEADOWS("blossom_meadows"),
    MOONLIGHT_GARDEN("moonlight_garden"),
    EMERALD_GROVE("emerald_grove"),
    FROSTED_BLOSSOM_VALLEY("frosted_blossom_valley"),
    ANCIENT_BLOSSOM_SANCTUARY("ancient_blossom_sanctuary");

    public final String id;

    CustomBiome(String id) {
        this.id = id;
    }

    public String key() {
        return "blossom:" + id;
    }

    public static CustomBiome byKey(String key) {
        for (CustomBiome biome : values()) {
            if (biome.key().equals(key) || biome.id.equals(key)) {
                return biome;
            }
        }
        return null;
    }
}
