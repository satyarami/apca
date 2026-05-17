package com.satya.apca;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Bar(
                @JsonProperty("t") Instant timestamp,
                @JsonProperty("o") BigDecimal open,
                @JsonProperty("h") BigDecimal high,
                @JsonProperty("l") BigDecimal low,
                @JsonProperty("c") BigDecimal close,
                @JsonProperty("v") long volume,
                @JsonProperty("vw") BigDecimal vwap,
                @JsonProperty("n") long tradeCount) {
}
