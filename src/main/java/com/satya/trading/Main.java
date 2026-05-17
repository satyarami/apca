package com.satya.apca;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class Main {

    private static final DateTimeFormatter DATE_ET = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("America/New_York"));

    public static void main(String[] args) throws Exception {
        var client = AlpacaClient.paperFromEnv();

        var bars = client.getDailyBars("AAPL", 10);

        System.out.println("AAPL last " + bars.size() + " daily closes (IEX feed):");
        System.out.printf("  %-12s  %10s%n", "Date (ET)", "Close");
        for (var bar : bars) {
            System.out.printf("  %-12s  %10s%n", DATE_ET.format(bar.timestamp()), bar.close());
        }
    }
}
