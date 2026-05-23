package com.satya.trading;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Allocation(
        String id,
        String symbol,
        BigDecimal qty,
        String strategyType,
        BigDecimal trailHigh,
        String stopOrderId) {

    public static final String TRAILING_STOP = "trailing_stop";
    public static final String DO_NOTHING = "do_nothing";

    public static Allocation create(String symbol, BigDecimal qty, String strategyType) {
        return new Allocation(UUID.randomUUID().toString(), symbol, qty, strategyType, null, null);
    }

    public Allocation withTrail(BigDecimal high, String orderId) {
        return new Allocation(id, symbol, qty, strategyType, high, orderId);
    }

    public static String label(String strategyType) {
        return switch (strategyType) {
            case TRAILING_STOP -> "Trailing Stop";
            case DO_NOTHING -> "Do Nothing";
            default -> strategyType;
        };
    }
}
