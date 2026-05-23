package com.satya.trading;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Account(
                String id,
                @JsonProperty("account_number") String accountNumber,
                String status,
                String currency,
                BigDecimal cash,
                BigDecimal equity,
                @JsonProperty("last_equity") BigDecimal lastEquity,
                @JsonProperty("buying_power") BigDecimal buyingPower,
                @JsonProperty("portfolio_value") BigDecimal portfolioValue,
                @JsonProperty("pattern_day_trader") boolean patternDayTrader,
                @JsonProperty("trading_blocked") boolean tradingBlocked,
                @JsonProperty("account_blocked") boolean accountBlocked) {
}
