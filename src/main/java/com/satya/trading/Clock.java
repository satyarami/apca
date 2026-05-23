package com.satya.trading;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Clock(
        Instant timestamp,
        @JsonProperty("is_open") boolean isOpen,
        @JsonProperty("next_open") Instant nextOpen,
        @JsonProperty("next_close") Instant nextClose) {
}
