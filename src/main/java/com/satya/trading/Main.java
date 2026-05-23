package com.satya.trading;

import java.nio.file.Path;

public final class Main {

    public static void main(String[] args) throws Exception {
        var client = AlpacaClient.paperFromEnv();
        var stateFile = Path.of(".trailing-stops.json");
        new TrailingStopStrategy(client, stateFile).run();
    }
}
