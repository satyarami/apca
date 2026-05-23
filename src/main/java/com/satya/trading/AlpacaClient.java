package com.satya.trading;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AlpacaClient {

    public static final String PAPER_TRADING_BASE = "https://paper-api.alpaca.markets";
    public static final String LIVE_TRADING_BASE = "https://api.alpaca.markets";
    public static final String DATA_BASE = "https://data.alpaca.markets";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String keyId;
    private final String secretKey;
    private final String baseUrl;

    public AlpacaClient(String keyId, String secretKey, String baseUrl) {
        this.keyId = keyId;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public static AlpacaClient paperFromEnv() {
        var keyId = System.getenv("APCA_API_KEY_ID");
        var secret = System.getenv("APCA_API_SECRET_KEY");
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Set APCA_API_KEY_ID and APCA_API_SECRET_KEY environment variables " +
                            "(get them from https://app.alpaca.markets/paper/dashboard/overview)");
        }
        return new AlpacaClient(keyId, secret, PAPER_TRADING_BASE);
    }

    public Account getAccount() throws IOException, InterruptedException {
        var response = send(authedRequest(baseUrl + "/v2/account").GET().build());
        ensureSuccess(response, "GET /v2/account");
        return mapper.readValue(response.body(), Account.class);
    }

    public List<Position> listPositions() throws IOException, InterruptedException {
        var response = send(authedRequest(baseUrl + "/v2/positions").GET().build());
        ensureSuccess(response, "GET /v2/positions");
        return mapper.readValue(response.body(), new TypeReference<List<Position>>() {
        });
    }

    public Clock getClock() throws IOException, InterruptedException {
        var response = send(authedRequest(baseUrl + "/v2/clock").GET().build());
        ensureSuccess(response, "GET /v2/clock");
        return mapper.readValue(response.body(), Clock.class);
    }

    /** Closes the entire position for {@code symbol} via a market order. Returns the submitted order. */
    public Order closePosition(String symbol) throws IOException, InterruptedException {
        var url = baseUrl + "/v2/positions/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        var response = send(authedRequest(url).DELETE().build());
        ensureSuccess(response, "DELETE /v2/positions/" + symbol);
        return mapper.readValue(response.body(), Order.class);
    }

    public Order placeMarketOrder(String symbol, int qty, OrderSide side)
            throws IOException, InterruptedException {
        var body = Map.of(
                "symbol", symbol,
                "qty", String.valueOf(qty),
                "side", side.api(),
                "type", "market",
                "time_in_force", "day");
        return submitOrder(body);
    }

    /**
     * Submit a stop (market-on-trigger) order with time_in_force=GTC.
     * stopPrice is rounded down to 2 decimals to satisfy Alpaca's sub-penny rules.
     */
    public Order placeStopOrder(String symbol, BigDecimal qty, OrderSide side, BigDecimal stopPrice)
            throws IOException, InterruptedException {
        var body = Map.of(
                "symbol", symbol,
                "qty", qty.stripTrailingZeros().toPlainString(),
                "side", side.api(),
                "type", "stop",
                "time_in_force", "gtc",
                "stop_price", stopPrice.setScale(2, RoundingMode.DOWN).toPlainString());
        return submitOrder(body);
    }

    private Order submitOrder(Map<String, ?> body) throws IOException, InterruptedException {
        var json = mapper.writeValueAsString(body);
        var request = authedRequest(baseUrl + "/v2/orders")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        var response = send(request);
        ensureSuccess(response, "POST /v2/orders");
        return mapper.readValue(response.body(), Order.class);
    }

    public List<Order> listOpenOrders() throws IOException, InterruptedException {
        var url = baseUrl + "/v2/orders?status=open&limit=500";
        var response = send(authedRequest(url).GET().build());
        ensureSuccess(response, "GET /v2/orders?status=open");
        return mapper.readValue(response.body(), new TypeReference<List<Order>>() {
        });
    }

    public Order getOrder(String orderId) throws IOException, InterruptedException {
        var response = send(authedRequest(baseUrl + "/v2/orders/" + orderId).GET().build());
        ensureSuccess(response, "GET /v2/orders/" + orderId);
        return mapper.readValue(response.body(), Order.class);
    }

    /** Returns true if the cancel was accepted; false if the order is already terminal (filled/canceled). */
    public boolean cancelOrder(String orderId) throws IOException, InterruptedException {
        var request = authedRequest(baseUrl + "/v2/orders/" + orderId).DELETE().build();
        var response = send(request);
        int code = response.statusCode();
        if (code == 204) return true;
        if (code == 422) return false;
        throw new IOException("DELETE /v2/orders/" + orderId + " failed: HTTP " + code + " body=" + response.body());
    }

    /** Most-recent trade price for {@code symbol} on the IEX feed (free tier). */
    public BigDecimal getLatestTradePrice(String symbol) throws IOException, InterruptedException {
        var url = DATA_BASE + "/v2/stocks/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "/trades/latest?feed=iex";
        var response = send(authedRequest(url).GET().build());
        ensureSuccess(response, "GET /v2/stocks/" + symbol + "/trades/latest");
        var root = mapper.readTree(response.body());
        var priceNode = root.path("trade").path("p");
        if (priceNode.isMissingNode() || priceNode.isNull()) {
            throw new IOException("No trade price in response: " + response.body());
        }
        return new BigDecimal(priceNode.asText());
    }

    /**
     * Fetch up to {@code limit} most-recent daily bars for {@code symbol}.
     * Uses the IEX feed (available on the free data tier).
     */
    public List<Bar> getDailyBars(String symbol, int limit)
            throws IOException, InterruptedException {
        var end = Instant.now();
        // Pull a wider window than `limit` to account for weekends/holidays.
        var start = end.minus(Duration.ofDays(Math.max(20L, limit * 2L)));

        var url = DATA_BASE + "/v2/stocks/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "/bars"
                + "?timeframe=1Day"
                + "&start=" + URLEncoder.encode(start.toString(), StandardCharsets.UTF_8)
                + "&end=" + URLEncoder.encode(end.toString(), StandardCharsets.UTF_8)
                + "&limit=1000"
                + "&adjustment=raw"
                + "&feed=iex";

        var response = send(authedRequest(url).GET().build());
        ensureSuccess(response, "GET /v2/stocks/" + symbol + "/bars");

        var root = mapper.readTree(response.body());
        var barsNode = root.get("bars");
        if (barsNode == null || barsNode.isNull()) {
            return List.of();
        }
        List<Bar> all = mapper.convertValue(barsNode, new TypeReference<List<Bar>>() {
        });
        // Alpaca returns bars in ascending time order; keep the most recent `limit`.
        if (all.size() <= limit)
            return all;
        return all.subList(all.size() - limit, all.size());
    }

    private HttpRequest.Builder authedRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secretKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15));
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void ensureSuccess(HttpResponse<String> response, String label) throws IOException {
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException(label + " failed: HTTP " + code + " body=" + response.body());
        }
    }
}
