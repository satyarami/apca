package com.satya.apca;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
        String id,
        @JsonProperty("client_order_id") String clientOrderId,
        String symbol,
        String side,
        @JsonProperty("type") String orderType,
        @JsonProperty("time_in_force") String timeInForce,
        BigDecimal qty,
        @JsonProperty("filled_qty") BigDecimal filledQty,
        @JsonProperty("filled_avg_price") BigDecimal filledAvgPrice,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("submitted_at") Instant submittedAt,
        @JsonProperty("filled_at") Instant filledAt
) {}
