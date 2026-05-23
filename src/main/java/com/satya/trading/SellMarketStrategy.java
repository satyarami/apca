package com.satya.trading;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * A sell strategy that immediately executes a market sell order at the current
 * market price.
 * This strategy prioritizes speed of execution over price certainty.
 */
public final class SellMarketStrategy {

    private final AlpacaClient client;

    public SellMarketStrategy(AlpacaClient client) {
        this.client = client;
    }

    /**
     * Execute an immediate market sell order for the given quantity of a symbol.
     *
     * @param symbol the stock symbol to sell (e.g., "AAPL")
     * @param qty    the quantity to sell
     * @return the submitted sell order
     * @throws IOException          if the API request fails
     * @throws InterruptedException if the request is interrupted
     */
    public Order executeSell(String symbol, int qty) throws IOException, InterruptedException {
        return client.placeMarketOrder(symbol, qty, OrderSide.SELL);
    }

    /**
     * Close an entire position for a symbol via a market sell order.
     *
     * @param symbol the stock symbol to sell
     * @return the submitted sell order that closes the position
     * @throws IOException          if the API request fails
     * @throws InterruptedException if the request is interrupted
     */
    public Order closePosition(String symbol) throws IOException, InterruptedException {
        return client.closePosition(symbol);
    }
}
