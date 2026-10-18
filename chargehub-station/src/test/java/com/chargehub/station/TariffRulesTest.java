package com.chargehub.station;

import com.chargehub.infrastructure.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TariffRulesTest {
    @Test
    void acceptsFullDayAndSortsBeforeValidation() {
        var result = TariffRules.canonicalSlots(List.of(slot(480, 1440), slot(0, 480)), true);
        assertEquals(0, result.getFirst().get("startMinute"));
        assertEquals(1440, result.getLast().get("endMinute"));
    }

    @Test
    void rejectsGapsOverlapsAndMissingEdgesAtPublication() {
        assertThrows(BusinessException.class, () -> TariffRules.canonicalSlots(List.of(slot(0, 480), slot(481, 1440)), true));
        assertThrows(BusinessException.class, () -> TariffRules.canonicalSlots(List.of(slot(0, 481), slot(480, 1440)), true));
        assertThrows(BusinessException.class, () -> TariffRules.canonicalSlots(List.of(slot(1, 1440)), true));
        assertThrows(BusinessException.class, () -> TariffRules.canonicalSlots(List.of(slot(0, 1439)), true));
    }

    @Test
    void allowsIncompleteDraftButRejectsFractionalOrNegativeRates() {
        assertEquals(1, TariffRules.canonicalSlots(List.of(slot(480, 1080)), false).size());
        assertThrows(BusinessException.class, () -> TariffRules.canonicalSlots(List.of(Map.of(
                "startMinute", 0, "endMinute", 1440, "energyRateFenPerKwh", 80.5, "serviceRateFenPerKwh", 50)), true));
        assertThrows(BusinessException.class, () -> TariffRules.canonicalSlots(List.of(Map.of(
                "startMinute", 0, "endMinute", 1440, "energyRateFenPerKwh", 80, "serviceRateFenPerKwh", -1)), true));
    }

    private static Map<String, Object> slot(int start, int end) {
        return Map.of("startMinute", start, "endMinute", end, "energyRateFenPerKwh", 80, "serviceRateFenPerKwh", 50);
    }
}
