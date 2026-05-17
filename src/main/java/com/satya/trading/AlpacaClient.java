package com.satya.apca;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
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

    public Order placeMarketOrder(String symbol, int qty, OrderSide side)
            throws IOException, InterruptedException {
        var body = Map.of(
                "symbol", symbol,
                "qty", String.valueOf(qty),
                "side", side.api(),
                "type", "market",
                "time_in_force", "day");
        var json = mapper.writeValueAsString(body);

        var request = authedRequest(baseUrl + "/v2/orders")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        var response = send(request);
        ensureSuccess(response, "POST /v2/orders");
        return mapper.readValue(response.body(), Order.class);
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
