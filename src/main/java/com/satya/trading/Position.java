package com.satya.trading;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Position(
        String symbol,
        @JsonProperty("asset_id") String assetId,
        BigDecimal qty,
        @JsonProperty("avg_entry_price") BigDecimal avgEntryPrice,
        String side,
        @JsonProperty("current_price") BigDecimal currentPrice,
        @JsonProperty("market_value") BigDecimal marketValue,
        @JsonProperty("unrealized_pl") BigDecimal unrealizedPl) {
}
