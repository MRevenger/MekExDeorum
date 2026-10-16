package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Tests for {@link EjectDirection}: config names, the default and unknown names. */
class EjectDirectionTest {

    @Test
    void theThreeSpecifiedDirectionsExist() {
        assertEquals(3, EjectDirection.values().length);
        assertEquals(EjectDirection.ALL_SIDES, EjectDirection.valueOf("ALL_SIDES"));
        assertEquals(EjectDirection.ALL_SIDES_EXCEPT_FRONT, EjectDirection.valueOf("ALL_SIDES_EXCEPT_FRONT"));
        assertEquals(EjectDirection.BACK, EjectDirection.valueOf("BACK"));
    }

    @Test
    void theDefaultIsAllSidesExceptFront() {
        assertEquals(EjectDirection.ALL_SIDES_EXCEPT_FRONT, EjectDirection.DEFAULT);
        assertEquals("all_sides_except_front", EjectDirection.DEFAULT.serializedName());
    }

    @Test
    void theConfiguredNamesResolve() {
        assertEquals(EjectDirection.ALL_SIDES, EjectDirection.byName("all_sides"));
        assertEquals(EjectDirection.ALL_SIDES_EXCEPT_FRONT, EjectDirection.byName("all_sides_except_front"));
        assertEquals(EjectDirection.BACK, EjectDirection.byName("back"));
    }

    @Test
    void matchingIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(EjectDirection.BACK, EjectDirection.byName("BACK"));
        assertEquals(EjectDirection.BACK, EjectDirection.byName("  Back  "));
        assertEquals(EjectDirection.ALL_SIDES, EjectDirection.byName("All_Sides"));
    }

    @Test
    void everyDirectionRoundTripsThroughItsSerialisedName() {
        for (EjectDirection direction : EjectDirection.values()) {
            assertEquals(direction, EjectDirection.byName(direction.serializedName()));
        }
    }

    @Test
    void unknownNamesFallBackToTheDefault() {
        assertEquals(EjectDirection.ALL_SIDES_EXCEPT_FRONT, EjectDirection.byName("nonsense"));
        assertEquals(EjectDirection.ALL_SIDES_EXCEPT_FRONT, EjectDirection.byName(""));
        assertEquals(EjectDirection.ALL_SIDES_EXCEPT_FRONT, EjectDirection.byName("   "));
        assertEquals(EjectDirection.ALL_SIDES_EXCEPT_FRONT, EjectDirection.byName(null));
    }
}
