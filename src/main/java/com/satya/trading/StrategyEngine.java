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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class StrategyEngine {

    public static final BigDecimal STOP_DROP = new BigDecimal("0.10");
    public static final BigDecimal RATCHET_GAIN = new BigDecimal("0.03");
    public static final BigDecimal FLOOR_MULT = BigDecimal.ONE.subtract(STOP_DROP);
    public static final BigDecimal RATCHET_MULT = BigDecimal.ONE.add(RATCHET_GAIN);

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(30);
    private static final Duration CLOSED_SLEEP = Duration.ofMinutes(5);
    private static final Duration ERROR_SLEEP = Duration.ofSeconds(15);

    private final AlpacaClient client;
    private final Path stateFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Allocation> allocations = new CopyOnWriteArrayList<>();

    public StrategyEngine(AlpacaClient client, Path stateFile) {
        this.client = client;
        this.stateFile = stateFile;
        loadState();
    }

    public static BigDecimal floorOf(BigDecimal high) {
        return high == null ? null : high.multiply(FLOOR_MULT);
    }

    /** Defensive copy, safe to call from any thread. */
    public List<Allocation> snapshot() {
        return new ArrayList<>(allocations);
    }

    public synchronized void addAllocation(Allocation a) {
        for (int i = 0; i < allocations.size(); i++) {
            Allocation existing = allocations.get(i);
            if (existing.symbol().equals(a.symbol())
                    && existing.strategyType().equals(a.strategyType())) {
                BigDecimal mergedQty = (existing.qty() == null ? BigDecimal.ZERO : existing.qty())
                        .add(a.qty() == null ? BigDecimal.ZERO : a.qty());
                String oldOrderId = existing.stopOrderId();
                Allocation merged = new Allocation(
                        existing.id(),
                        existing.symbol(),
                        mergedQty,
                        existing.strategyType(),
                        existing.trailHigh(),
                        null);
                allocations.set(i, merged);
                saveState();
                if (oldOrderId != null) {
                    try {
                        client.cancelOrder(oldOrderId);
                    } catch (Exception e) {
                        log("Could not cancel stop " + oldOrderId + " on merge: " + e.getMessage());
                    }
                }
                log("Merged +" + a.qty() + " into " + shortId(existing.id())
                        + " (" + a.symbol() + " " + Allocation.label(a.strategyType())
                        + ", qty=" + mergedQty + ")");
                return;
            }
        }
        allocations.add(a);
        saveState();
    }

    public synchronized boolean removeAllocation(String id) {
        Allocation removed = null;
        for (int i = 0; i < allocations.size(); i++) {
            if (allocations.get(i).id().equals(id)) {
                removed = allocations.remove(i);
                break;
            }
        }
        if (removed == null) return false;
        saveState();
        if (removed.stopOrderId() != null) {
            try {
                client.cancelOrder(removed.stopOrderId());
            } catch (Exception e) {
                log("Could not cancel stop " + removed.stopOrderId()
                        + " for removed allocation " + removed.id() + ": " + e.getMessage());
            }
        }
        return true;
    }

    public void run() throws InterruptedException {
        log("Engine starting: state=" + stateFile.toAbsolutePath());
        while (!Thread.currentThread().isInterrupted()) {
            Duration sleep = POLL_INTERVAL;
            try {
                var clock = client.getClock();
                if (!clock.isOpen()) {
                    sleep = CLOSED_SLEEP;
                }
                tick();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                log("ERROR in loop: " + e.getMessage());
                sleep = ERROR_SLEEP;
            }
            Thread.sleep(sleep.toMillis());
        }
    }

    private void tick() throws IOException, InterruptedException {
        List<Position> positions = client.listPositions();
        Map<String, BigDecimal> qtyBySymbol = new HashMap<>();
        for (Position p : positions) qtyBySymbol.put(p.symbol(), p.qty());

        for (Allocation a : new ArrayList<>(allocations)) {
            try {
                BigDecimal posQty = qtyBySymbol.get(a.symbol());
                if (posQty == null || posQty.signum() <= 0) {
                    log("Removing orphan allocation " + shortId(a.id()) + " for "
                            + a.symbol() + " (no position)");
                    removeAllocationSilently(a.id());
                    continue;
                }
                switch (a.strategyType()) {
                    case Allocation.TRAILING_STOP -> handleTrailingStop(a);
                    case Allocation.DO_NOTHING -> {}
                    default -> log("Unknown strategy type: " + a.strategyType());
                }
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                log("ERROR handling allocation " + shortId(a.id())
                        + " (" + a.symbol() + "): " + e.getMessage());
            }
        }
    }

    private void handleTrailingStop(Allocation a) throws IOException, InterruptedException {
        BigDecimal price = client.getLatestTradePrice(a.symbol());
        if (a.trailHigh() == null) {
            placeNewStop(a, price, price, "initial");
            return;
        }
        BigDecimal ratchetTrigger = a.trailHigh().multiply(RATCHET_MULT);
        if (price.compareTo(ratchetTrigger) >= 0) {
            if (a.stopOrderId() != null) {
                try {
                    client.cancelOrder(a.stopOrderId());
                } catch (Exception e) {
                    log("[" + a.symbol() + "] cancel of " + a.stopOrderId()
                            + " failed: " + e.getMessage());
                }
            }
            placeNewStop(a, price, price, "ratchet from high=" + a.trailHigh());
        }
    }

    private void placeNewStop(Allocation a, BigDecimal newHigh, BigDecimal triggerPrice, String reason)
            throws IOException, InterruptedException {
        BigDecimal stopPx = newHigh.multiply(FLOOR_MULT);
        Order order = client.placeStopOrder(a.symbol(), a.qty(), OrderSide.SELL, stopPx);
        replaceAllocation(a.id(), a.withTrail(newHigh, order.id()));
        saveState();
        log("[" + a.symbol() + "][" + shortId(a.id()) + "] " + reason
                + ": price=" + triggerPrice + " high=" + newHigh
                + " stop=" + money(stopPx) + " orderId=" + order.id());
    }

    private synchronized void replaceAllocation(String id, Allocation updated) {
        for (int i = 0; i < allocations.size(); i++) {
            if (allocations.get(i).id().equals(id)) {
                allocations.set(i, updated);
                return;
            }
        }
    }

    private synchronized void removeAllocationSilently(String id) {
        for (int i = 0; i < allocations.size(); i++) {
            if (allocations.get(i).id().equals(id)) {
                allocations.remove(i);
                saveState();
                return;
            }
        }
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }

    private static String money(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            List<Allocation> raw = mapper.readValue(Files.readAllBytes(stateFile),
                    new TypeReference<List<Allocation>>() {
                    });
            allocations.addAll(raw);
            log("Loaded " + allocations.size() + " allocation(s)");
        } catch (IOException e) {
            log("Could not load allocations from " + stateFile + ": " + e.getMessage());
        }
    }

    private synchronized void saveState() {
        try {
            Files.writeString(stateFile,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(allocations));
        } catch (IOException e) {
            log("Could not save state to " + stateFile + ": " + e.getMessage());
        }
    }

    private static void log(String msg) {
        System.out.println("[" + Instant.now() + "] " + msg);
    }
}
