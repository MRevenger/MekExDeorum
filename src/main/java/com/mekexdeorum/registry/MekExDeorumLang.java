package com.mekexdeorum.registry;

import com.mekexdeorum.MekExDeorum;
import mekanism.api.text.ILangEntry;

/**
 * Translation keys used by the machine descriptions and the container title.
 *
 * <p>Mekanism builds a key as {@code <type>.<modid>.<path>}, so these constants resolve to
 * {@code description.mekexdeorum.*} and {@code container.mekexdeorum.sieve}, exactly the keys
 * written into {@code assets/mekexdeorum/lang}.
 */
public enum MekExDeorumLang implements ILangEntry {
    DESCRIPTION_UNIVERSAL_SIEVE("description", "universal_sieve"),
    DESCRIPTION_BASIC_SIEVE_FACTORY("description", "basic_sieve_factory"),
    DESCRIPTION_ADVANCED_SIEVE_FACTORY("description", "advanced_sieve_factory"),
    DESCRIPTION_ELITE_SIEVE_FACTORY("description", "elite_sieve_factory"),
    DESCRIPTION_ULTIMATE_SIEVE_FACTORY("description", "ultimate_sieve_factory"),
    AUTO_SPLIT("gui", "auto_split"),
    SIEVE("container", "sieve"),
    MEKEXDEORUM("itemGroup", "mekexdeorum");

    private final String key;

    MekExDeorumLang(String type, String path) {
        this.key = type + "." + MekExDeorum.MOD_ID + "." + path;
    }

    @Override
    public String getTranslationKey() {
        return key;
    }
}
