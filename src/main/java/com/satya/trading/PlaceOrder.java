package com.satya.trading;

public final class PlaceOrder {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: PlaceOrder <symbol> <qty> <buy|sell>");
            System.exit(1);
        }
        var symbol = args[0];
        var qty = Integer.parseInt(args[1]);
        var side = OrderSide.valueOf(args[2].toUpperCase());

        var client = AlpacaClient.paperFromEnv();
        System.out.println("Placing market " + side + " " + qty + " " + symbol + "...");
        var order = client.placeMarketOrder(symbol, qty, side);
        System.out.printf("  id=%s symbol=%s qty=%s status=%s%n",
                order.id(), order.symbol(), order.qty(), order.status());
    }
}
