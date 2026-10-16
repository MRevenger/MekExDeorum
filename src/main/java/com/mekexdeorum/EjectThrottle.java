package com.mekexdeorum;

/**
 * Eject throttle of a machine that has not managed to push anything out for a while.
 *
 * <p>Pure Java state machine, driven by the block entity once per game tick:
 *
 * <pre>
 * if (throttle.tick()) {                 // true = this tick should attempt an eject
 *     boolean ejected = tryEjectAll();
 *     throttle.report(ejected);
 * }
 * </pre>
 *
 * <p>{@link #tick()} returns {@code false} while the cooldown runs, which is what reduces an idle
 * machine from one attempt per tick to one per {@link #COOLDOWN_TICKS} ticks. The two constants are
 * hard-coded on purpose: the specification fixes them and they are not configurable.
 */
public final class EjectThrottle {

    /** Failed attempts in a row after which the machine starts throttling. */
    public static final int IDLE_THRESHOLD = 200;

    /** Ticks skipped between two attempts while throttling. */
    public static final int COOLDOWN_TICKS = 20;

    private boolean dynamic = true;
    private int idleTicks;
    private int cooldown;

    /**
     * Refreshes the {@code dynamicEject} setting, which the caller reads every tick.
     *
     * <p>Turning it off clears both counters: with throttling off the machine always attempts and
     * never accumulates idle time.
     */
    public void setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
        if (!dynamic) {
            idleTicks = 0;
            cooldown = 0;
        }
    }

    /**
     * One game tick. Returns {@code true} when the caller should attempt an eject this tick and
     * must then call {@link #report(boolean)}; returns {@code false} while the cooldown lasts.
     */
    public boolean tick() {
        if (!dynamic) {
            return true;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        return true;
    }

    /**
     * Reports the outcome of an attempt made this tick. A successful eject resets the idle timer;
     * enough failed attempts in a row start the cooldown.
     */
    public void report(boolean ejected) {
        if (!dynamic) {
            return;
        }
        if (ejected) {
            idleTicks = 0;
        } else {
            idleTicks++;
        }
        if (idleTicks >= IDLE_THRESHOLD) {
            cooldown = COOLDOWN_TICKS;
        }
    }

    /** Failed attempts in a row; reset by a successful eject. */
    public int idleTicks() {
        return idleTicks;
    }

    /** Ticks still skipped before the next attempt; 0 when not throttling. */
    public int cooldown() {
        return cooldown;
    }
}
