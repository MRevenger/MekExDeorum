package com.mekexdeorum;

import java.util.Locale;

/**
 * Sides the machine pushes its finished results to.
 *
 * <p>Pure Java on purpose: the config stores the serialised name, the machine asks for the enum,
 * and the unit tests need neither a Minecraft nor a Mekanism class path.
 */
public enum EjectDirection {

    /** All six sides, including the side the machine is facing. */
    ALL_SIDES,
    /** All sides except the front face. The default. */
    ALL_SIDES_EXCEPT_FRONT,
    /** The back face only. */
    BACK;

    /** Fallback for an unknown name. */
    public static final EjectDirection DEFAULT = ALL_SIDES_EXCEPT_FRONT;

    /**
     * Resolves a configured name such as {@code "all_sides_except_front"}.
     *
     * <p>Matching ignores case and surrounding whitespace. An unknown, empty or {@code null} name
     * yields {@link #DEFAULT} rather than an exception, so a typo in the config file cannot stop
     * a machine from working.
     */
    public static EjectDirection byName(String name) {
        if (name != null) {
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            for (EjectDirection candidate : values()) {
                if (candidate.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return candidate;
                }
            }
        }
        return DEFAULT;
    }

    /** The name to write into the config file. */
    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
