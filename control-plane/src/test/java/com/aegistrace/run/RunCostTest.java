package com.aegistrace.run;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunCostTest {
    @Test
    void zeroTokensAreNotPresentedAsAPrice() {
        Map<String, Object> cost = RunService.runCost("grounded-extractive-v1", 0, 0, BigDecimal.ZERO);
        assertEquals("NO_TOKENS", cost.get("pricingStatus"));
        assertNull(cost.get("amount"));
        assertEquals(0, cost.get("tokens"));
    }

    @Test
    void groundedExtractiveIsConfiguredZero() {
        Map<String, Object> cost = RunService.runCost("grounded-extractive-v1", 200, 50, BigDecimal.ZERO);
        assertEquals("CONFIGURED_ZERO", cost.get("pricingStatus"));
        assertEquals(0, ((BigDecimal) cost.get("amount")).compareTo(BigDecimal.ZERO));
        assertEquals(250, cost.get("tokens"));
    }

    @Test
    void unknownModelsDoNotInventAPrice() {
        Map<String, Object> cost = RunService.runCost("mystery-model", 10, 10, BigDecimal.ZERO);
        assertEquals("PRICING_UNAVAILABLE", cost.get("pricingStatus"));
        assertNull(cost.get("amount"));
    }

    @Test
    void listPricedModelsUseTheStoredAmount() {
        Map<String, Object> cost = RunService.runCost("gpt-4o-mini", 1000, 500, new BigDecimal("0.75"));
        assertEquals("PRICED", cost.get("pricingStatus"));
        assertEquals(0, new BigDecimal("0.75").compareTo((BigDecimal) cost.get("amount")));
    }
}
