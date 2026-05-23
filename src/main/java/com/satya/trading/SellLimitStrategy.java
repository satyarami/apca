package com.satya.trading;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * A sell strategy that executes a limit sell order at a specified price.
 * This strategy prioritizes price certainty over speed of execution.
 * The order remains open until filled or cancelled.
 */
public final class SellLimitStrategy {

    private final AlpacaClient client;

    public SellLimitStrategy(AlpacaClient client) {
        this.client = client;
    }

    /**
     * Execute a limit sell order for the given quantity of a symbol at the
     * specified price.
     * The order will only fill at the specified limit price or better (higher).
     *
     * @param symbol     the stock symbol to sell (e.g., "AAPL")
     * @param qty        the quantity to sell
     * @param limitPrice the minimum price to sell at (order fills at this price or
     *                   better)
     * @return the submitted sell order
     * @throws IOException          if the API request fails
     * @throws InterruptedException if the request is interrupted
     */
    public Order executeSell(String symbol, BigDecimal qty, BigDecimal limitPrice)
            throws IOException, InterruptedException {
        return client.placeLimitOrder(symbol, qty, OrderSide.SELL, limitPrice);
    }
}
