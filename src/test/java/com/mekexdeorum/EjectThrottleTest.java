package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EjectThrottle}: the 200 failed ticks / 20 tick cooldown state machine of the
 * specification, including the "never throttle" behaviour when dynamic ejection is off.
 */
class EjectThrottleTest {

    @Test
    void constantsAreTheSpecifiedOnes() {
        assertEquals(200, EjectThrottle.IDLE_THRESHOLD);
        assertEquals(20, EjectThrottle.COOLDOWN_TICKS);
    }

    @Test
    void aFreshThrottleAttemptsAndHasNoCounters() {
        EjectThrottle throttle = new EjectThrottle();
        assertEquals(0, throttle.idleTicks());
        assertEquals(0, throttle.cooldown());
        assertTrue(throttle.tick(), "a fresh throttle must attempt an eject");
    }

    @Test
    void failedAttemptsAccumulateIdleTicks() {
        EjectThrottle throttle = new EjectThrottle();
        for (int tick = 1; tick <= 10; tick++) {
            assertTrue(throttle.tick());
            throttle.report(false);
            assertEquals(tick, throttle.idleTicks());
            assertEquals(0, throttle.cooldown(), "no cooldown before the threshold");
        }
    }

    @Test
    void aSuccessfulEjectResetsTheIdleCounter() {
        EjectThrottle throttle = new EjectThrottle();
        for (int i = 0; i < 150; i++) {
            throttle.tick();
            throttle.report(false);
        }
        assertEquals(150, throttle.idleTicks());

        assertTrue(throttle.tick());
        throttle.report(true);
        assertEquals(0, throttle.idleTicks(), "a successful eject must reset the idle timer");
        assertEquals(0, throttle.cooldown());
    }

    @Test
    void twoHundredFailedTicksStartATwentyTickCooldown() {
        EjectThrottle throttle = new EjectThrottle();
        for (int tick = 1; tick <= EjectThrottle.IDLE_THRESHOLD; tick++) {
            assertTrue(throttle.tick(), "tick " + tick + " should still attempt");
            throttle.report(false);
        }
        assertEquals(200, throttle.idleTicks());
        assertEquals(EjectThrottle.COOLDOWN_TICKS, throttle.cooldown());

        // Exactly the next 20 ticks are skipped, then the machine tries again.
        for (int skipped = 0; skipped < EjectThrottle.COOLDOWN_TICKS; skipped++) {
            assertFalse(throttle.tick(), "tick inside the cooldown must not attempt");
        }
        assertEquals(0, throttle.cooldown());
        assertTrue(throttle.tick(), "the tick after the cooldown must attempt again");

        // Failing again immediately re-arms the cooldown, so the machine stays at one try per 20
        // ticks as long as nothing can be pushed out.
        throttle.report(false);
        assertEquals(201, throttle.idleTicks());
        assertEquals(EjectThrottle.COOLDOWN_TICKS, throttle.cooldown());
    }

    @Test
    void anIdleMachineEndsUpAtOneAttemptPerTwentyTicks() {
        EjectThrottle throttle = new EjectThrottle();
        int attempts = 0;
        for (int tick = 1; tick <= 300; tick++) {
            if (throttle.tick()) {
                attempts++;
                throttle.report(false);
            }
        }
        // 200 attempts while the machine is still trying every tick, then ticks 221, 242, 263 and
        // 284: 204 attempts out of 300 ticks instead of 300.
        assertEquals(204, attempts);
    }

    @Test
    void withDynamicEjectionOffEveryTickAttempts() {
        EjectThrottle throttle = new EjectThrottle();
        throttle.setDynamic(false);
        for (int tick = 1; tick <= 1000; tick++) {
            assertTrue(throttle.tick(), "throttling off must always attempt");
            throttle.report(false);
        }
        assertEquals(0, throttle.idleTicks(), "idle time must not accumulate while throttling is off");
        assertEquals(0, throttle.cooldown(), "no cooldown while throttling is off");
    }

    @Test
    void switchingDynamicEjectionOffClearsAStaleCooldown() {
        EjectThrottle throttle = new EjectThrottle();
        for (int i = 0; i < EjectThrottle.IDLE_THRESHOLD; i++) {
            throttle.tick();
            throttle.report(false);
        }
        assertEquals(EjectThrottle.COOLDOWN_TICKS, throttle.cooldown());

        throttle.setDynamic(false);
        assertEquals(0, throttle.cooldown());
        assertEquals(0, throttle.idleTicks());
        assertTrue(throttle.tick());
    }

    @Test
    void switchingDynamicEjectionBackOnRestartsFromIdle() {
        EjectThrottle throttle = new EjectThrottle();
        throttle.setDynamic(false);
        for (int i = 0; i < 500; i++) {
            throttle.tick();
            throttle.report(false);
        }
        throttle.setDynamic(true);
        assertTrue(throttle.tick());
        throttle.report(false);
        assertEquals(1, throttle.idleTicks());
        assertEquals(0, throttle.cooldown());
    }
}
