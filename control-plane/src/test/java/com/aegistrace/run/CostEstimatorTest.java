package com.aegistrace.run;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostEstimatorTest {
    @Test
    void offlineProviderHasNoInventedModelCharge() {
        assertEquals(0, RunService.estimateCost("grounded-extractive-v1", 1000, 500).compareTo(BigDecimal.ZERO));
    }

    @Test
    void miniModelUsesTheConfiguredListPrice() {
        BigDecimal cost = RunService.estimateCost("gpt-4o-mini", 1_000_000, 1_000_000);
        assertTrue(cost.subtract(new BigDecimal("0.75")).abs().doubleValue() < 0.0001);
    }
}
