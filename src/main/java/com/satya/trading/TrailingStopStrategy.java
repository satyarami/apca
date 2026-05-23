package com.satya.trading;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TrailingStopStrategy {

    private static final BigDecimal STOP_DROP = new BigDecimal("0.10");
    private static final BigDecimal RATCHET_GAIN = new BigDecimal("0.03");
    private static final BigDecimal FLOOR_MULT = BigDecimal.ONE.subtract(STOP_DROP);
    private static final BigDecimal RATCHET_MULT = BigDecimal.ONE.add(RATCHET_GAIN);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(30);
    private static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(5);
    private static final Duration ERROR_SLEEP = Duration.ofSeconds(15);

    public record TrailState(BigDecimal high, String stopOrderId) {
    }

    private final AlpacaClient client;
    private final Path stateFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, TrailState> state = new ConcurrentHashMap<>();
    private Instant lastSummaryAt = Instant.EPOCH;

    public TrailingStopStrategy(AlpacaClient client, Path stateFile) {
        this.client = client;
        this.stateFile = stateFile;
        loadState();
    }

    public void run() throws InterruptedException {
        log("Strategy starting: drop=" + STOP_DROP + ", ratchet=" + RATCHET_GAIN
                + ", poll=" + POLL_INTERVAL.toSeconds() + "s, state=" + stateFile.toAbsolutePath());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var clock = client.getClock();
                if (!clock.isOpen()) {
                    log("Market closed (next open " + clock.nextOpen() + "); printing summary only.");
                    var positions = client.listPositions();
                    printSummary(positions);
                    lastSummaryAt = Instant.now();
                    Thread.sleep(SUMMARY_INTERVAL.toMillis());
                    continue;
                }
                tick();
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                log("ERROR in loop: " + e.getMessage());
                Thread.sleep(ERROR_SLEEP.toMillis());
            }
        }
    }

    private void tick() throws IOException, InterruptedException {
        var positions = client.listPositions();
        var liveSymbols = symbolsOf(positions);

        var orphaned = new ArrayList<String>();
        for (var sym : state.keySet()) {
            if (!liveSymbols.contains(sym)) orphaned.add(sym);
        }
        for (var sym : orphaned) {
            var s = state.remove(sym);
            log("[" + sym + "] position gone; dropping state (high=" + s.high() + ")");
            if (s.stopOrderId() != null) {
                try {
                    client.cancelOrder(s.stopOrderId());
                } catch (Exception e) {
                    log("[" + sym + "] cancel of orphan stop " + s.stopOrderId() + " failed: " + e.getMessage());
                }
            }
        }
        if (!orphaned.isEmpty()) saveState();

        for (var pos : positions) {
            try {
                handlePosition(pos);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                log("ERROR handling " + pos.symbol() + ": " + e.getMessage());
            }
        }

        if (Duration.between(lastSummaryAt, Instant.now()).compareTo(SUMMARY_INTERVAL) >= 0) {
            try {
                printSummary(positions);
            } catch (Exception e) {
                log("ERROR printing summary: " + e.getMessage());
            }
            lastSummaryAt = Instant.now();
        }
    }

    private void handlePosition(Position pos) throws IOException, InterruptedException {
        var symbol = pos.symbol();
        if (!"long".equalsIgnoreCase(pos.side())) {
            log("[" + symbol + "] skipping non-long position (side=" + pos.side() + ")");
            return;
        }
        if (pos.qty() == null || pos.qty().signum() <= 0) {
            log("[" + symbol + "] skipping position with non-positive qty=" + pos.qty());
            return;
        }

        var price = client.getLatestTradePrice(symbol);
        var existing = state.get(symbol);

        if (existing == null) {
            placeNewStop(symbol, pos.qty(), price, price, "initial");
            return;
        }

        var ratchetTrigger = existing.high().multiply(RATCHET_MULT);
        if (price.compareTo(ratchetTrigger) >= 0) {
            try {
                client.cancelOrder(existing.stopOrderId());
            } catch (Exception e) {
                log("[" + symbol + "] cancel of " + existing.stopOrderId() + " failed: " + e.getMessage()
                        + " (will still try to place new stop)");
            }
            placeNewStop(symbol, pos.qty(), price, price,
                    "ratchet from high=" + existing.high());
        }
    }

    private void placeNewStop(String symbol, BigDecimal qty, BigDecimal newHigh, BigDecimal triggerPrice, String reason)
            throws IOException, InterruptedException {
        var stopPx = newHigh.multiply(FLOOR_MULT);
        var order = client.placeStopOrder(symbol, qty, OrderSide.SELL, stopPx);
        state.put(symbol, new TrailState(newHigh, order.id()));
        saveState();
        log("[" + symbol + "] " + reason + ": price=" + triggerPrice
                + " high=" + newHigh + " stop=" + money(stopPx) + " orderId=" + order.id());
    }

    private void printSummary(List<Position> positions) throws IOException, InterruptedException {
        var account = client.getAccount();
        var openOrders = client.listOpenOrders();
        var out = new StringBuilder();
        out.append(System.lineSeparator())
                .append("=== Portfolio @ ").append(Instant.now()).append(" ===").append(System.lineSeparator())
                .append(String.format("Equity: $%s   Cash: $%s   Buying power: $%s%n",
                        money(account.equity()), money(account.cash()), money(account.buyingPower())));
        out.append("--- Positions ---").append(System.lineSeparator());
        if (positions.isEmpty()) {
            out.append("  (no open positions)").append(System.lineSeparator());
        } else {
            out.append(String.format("  %-8s %10s %10s %10s %10s %10s %14s%n",
                    "SYMBOL", "QTY", "ENTRY", "LAST", "HIGH", "STOP", "UNREAL P&L"));
            for (var p : positions) {
                var s = state.get(p.symbol());
                var ref = s == null ? null : s.high();
                var stop = ref == null ? null : ref.multiply(FLOOR_MULT);
                out.append(String.format("  %-8s %10s %10s %10s %10s %10s %14s%n",
                        p.symbol(),
                        plain(p.qty()),
                        money(p.avgEntryPrice()),
                        money(p.currentPrice()),
                        money(ref),
                        money(stop),
                        money(p.unrealizedPl())));
            }
        }
        out.append("--- Pending orders ---").append(System.lineSeparator());
        if (openOrders.isEmpty()) {
            out.append("  (no pending orders)").append(System.lineSeparator());
        } else {
            out.append(String.format("  %-8s %6s %-10s %10s %10s %14s%n",
                    "SYMBOL", "SIDE", "TYPE", "QTY", "PX", "STATUS"));
            for (var o : openOrders) {
                var px = o.stopPrice() != null ? o.stopPrice()
                        : o.limitPrice() != null ? o.limitPrice() : null;
                out.append(String.format("  %-8s %6s %-10s %10s %10s %14s%n",
                        o.symbol(),
                        o.side(),
                        o.orderType(),
                        plain(o.qty()),
                        money(px),
                        o.status()));
            }
        }
        System.out.print(out);
    }

    private static Set<String> symbolsOf(List<Position> positions) {
        var s = new HashSet<String>();
        for (var p : positions) s.add(p.symbol());
        return s;
    }

    private static String money(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            Map<String, TrailState> raw = mapper.readValue(Files.readAllBytes(stateFile),
                    new TypeReference<Map<String, TrailState>>() {
                    });
            state.putAll(raw);
            log("Loaded state for " + state.size() + " symbol(s)");
        } catch (IOException e) {
            log("Could not load state from " + stateFile + ": " + e.getMessage());
        }
    }

    private void saveState() {
        try {
            Files.writeString(stateFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        } catch (IOException e) {
            log("Could not save state to " + stateFile + ": " + e.getMessage());
        }
    }

    private static void log(String msg) {
        System.out.println("[" + Instant.now() + "] " + msg);
    }
}
