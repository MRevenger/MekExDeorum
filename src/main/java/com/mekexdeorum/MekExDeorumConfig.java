package com.mekexdeorum;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * All tunables for the Sieve Machine and its factory tiers.
 *
 * <p>COMMON, because the slot counts and the eject layout must match on the client and the server:
 * the menu and the screen are derived from them. Values are read on demand and never cached in a
 * static field, so an edited file applies to the next machine placed or loaded.
 *
 * <p>Every entry declares an explicit {@code .translation(...)} key: the missing-key bug of the
 * old version came from entries that relied on the generated key. The authoritative list of these
 * keys is {@code _plan/LANG_KEYS.md}.
 */
public final class MekExDeorumConfig {

    public static final ModConfigSpec SPEC;

    // --- machine ---
    public static final ModConfigSpec.IntValue OUTPUT_SLOTS;
    public static final ModConfigSpec.BooleanValue MESH_DAMAGE;

    // --- enchantment ---
    public static final ModConfigSpec.DoubleValue EFFICIENCY_SPEED_BONUS;
    public static final ModConfigSpec.DoubleValue FORTUNE_CHANCE_BONUS;
    public static final ModConfigSpec.DoubleValue FORTUNE_AMOUNT_BONUS;

    // --- eject ---
    public static final ModConfigSpec.BooleanValue DYNAMIC_EJECT;

    // --- energy ---
    public static final ModConfigSpec.IntValue BASE_ENERGY_CAPACITY;
    public static final ModConfigSpec.DoubleValue ENERGY_CAPACITY_MULTIPLIER;
    public static final ModConfigSpec.LongValue ENERGY_PER_ITEM;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                        "Settings for the Sieve Machine and its factory tiers.",
                        "Slot counts are shared by client and server, so both sides must use the same",
                        "values. Break and replace an existing machine to pick up a changed value.")
                .translation("mekexdeorum.configuration.machine")
                .push("machine");

        OUTPUT_SLOTS = builder
                .comment("Number of output slots of every sieve machine.",
                        "Each output slot is an oversized slot and may hold up to Integer.MAX_VALUE",
                        "items, so this only decides how many different results are visible at once.",
                        "Anything that does not fit goes into an internal overflow buffer instead of",
                        "being lost, and is merged in as soon as a slot frees up.",
                        "Range: 1 - 64.")
                .translation("mekexdeorum.configuration.machine.outputSlots")
                .defineInRange("outputSlots", 16, 1, 64);

        MESH_DAMAGE = builder
                .comment("Whether the installed mesh takes durability damage.",
                        "Only damageable meshes are affected: the six Ex Deorum meshes are not",
                        "damageable, so with Ex Deorum's own meshes this setting changes nothing.")
                .translation("mekexdeorum.configuration.machine.meshDamage")
                .define("meshDamage", false);

        builder.pop();

        builder.comment(
                        "How the Efficiency and Fortune enchantments on the installed mesh change",
                        "processing time and yield.")
                .translation("mekexdeorum.configuration.enchantment")
                .push("enchantment");

        EFFICIENCY_SPEED_BONUS = builder
                .comment("Fraction of the processing time removed per level of Efficiency.",
                        "0.15 means -15% per level. Range: 0.0 - 1.0.")
                .translation("mekexdeorum.configuration.enchantment.efficiencySpeedBonus")
                .defineInRange("efficiencySpeedBonus", 0.15D, 0.0D, 1.0D);

        FORTUNE_CHANCE_BONUS = builder
                .comment("Absolute chance added to every result's drop chance per level of Fortune.",
                        "0.05 means +5 percentage points per level, capped at a chance of 1.0.",
                        "Range: 0.0 - 1.0.")
                .translation("mekexdeorum.configuration.enchantment.fortuneChanceBonus")
                .defineInRange("fortuneChanceBonus", 0.05D, 0.0D, 1.0D);

        FORTUNE_AMOUNT_BONUS = builder
                .comment("Relative increase of the rolled result amount per level of Fortune.",
                        "0.0 disables it; 0.5 means +50% per level. Range: 0.0 - 10.0.")
                .translation("mekexdeorum.configuration.enchantment.fortuneAmountBonus")
                .defineInRange("fortuneAmountBonus", 0.0D, 0.0D, 10.0D);

        builder.pop();

        builder.comment(
                        "How finished results are pushed into adjacent inventories.")
                .translation("mekexdeorum.configuration.eject")
                .push("eject");

        DYNAMIC_EJECT = builder
                .comment("Throttle the eject attempts of an idle machine.",
                        "Every tick all output slots are pushed into the neighbouring inventories.",
                        "With this on, a machine that has not managed to eject anything for 200 ticks",
                        "is treated as idle and only tries once every 20 ticks; a successful eject",
                        "resets that timer. Turn it off to always attempt an eject every tick.",
                        "The 200 tick and 20 tick numbers are fixed and not configurable.")
                .translation("mekexdeorum.configuration.eject.dynamicEject")
                .define("dynamicEject", true);

        builder.pop();

        builder.comment(
                        "Energy buffer sizes and the energy cost of sieving.")
                .translation("mekexdeorum.configuration.energy")
                .push("energy");

        BASE_ENERGY_CAPACITY = builder
                .comment("Energy buffer of the Universal Sieve (tier 0), in FE.")
                .translation("mekexdeorum.configuration.energy.baseEnergyCapacity")
                .defineInRange("baseEnergyCapacity", 20000, 1, Integer.MAX_VALUE);

        ENERGY_CAPACITY_MULTIPLIER = builder
                .comment("Energy buffer multiplier per factory tier: capacity = base * multiplier^tier.",
                        "2.0 gives 20000 / 40000 / 80000 / 160000 / 320000 FE across the five tiers.",
                        "Range: 1.0 - 1024.0.")
                .translation("mekexdeorum.configuration.energy.energyCapacityMultiplier")
                .defineInRange("energyCapacityMultiplier", 2.0D, 1.0D, 1024.0D);

        ENERGY_PER_ITEM = builder
                .comment("Energy consumed per input item of one sieving operation, in FE.",
                        "An operation that consumes n items draws n times this amount.")
                .translation("mekexdeorum.configuration.energy.energyPerItem")
                .defineInRange("energyPerItem", 200L, 1L, Long.MAX_VALUE);

        builder.pop();

        SPEC = builder.build();
    }

    private MekExDeorumConfig() {}

    /** Number of output slots every sieve machine has. */
    public static int outputSlots() {
        return OUTPUT_SLOTS.get();
    }

    /** Whether a damageable mesh loses durability per operation. */
    public static boolean meshDamage() {
        return MESH_DAMAGE.get();
    }

    /** Fraction of the processing time removed per level of Efficiency. */
    public static double efficiencySpeedBonus() {
        return EFFICIENCY_SPEED_BONUS.get();
    }

    /** Absolute drop-chance bonus per level of Fortune. */
    public static double fortuneChanceBonus() {
        return FORTUNE_CHANCE_BONUS.get();
    }

    /** Relative result-amount bonus per level of Fortune. */
    public static double fortuneAmountBonus() {
        return FORTUNE_AMOUNT_BONUS.get();
    }

    /** Whether an idle machine throttles its eject attempts. */
    public static boolean dynamicEject() {
        return DYNAMIC_EJECT.get();
    }

    /** Energy buffer of the Universal Sieve, in FE. */
    public static int baseEnergyCapacity() {
        return BASE_ENERGY_CAPACITY.get();
    }

    /** Energy buffer multiplier per factory tier. */
    public static double energyCapacityMultiplier() {
        return ENERGY_CAPACITY_MULTIPLIER.get();
    }

    /** Energy consumed per input item, in FE. */
    public static long energyPerItem() {
        return ENERGY_PER_ITEM.get();
    }

    /** Energy buffer of a machine of the given tier: base * multiplier^tier. */
    public static long capacityForTier(int tier) {
        int clampedTier = Math.max(0, tier);
        return (long) (baseEnergyCapacity() * Math.pow(energyCapacityMultiplier(), clampedTier));
    }
}
