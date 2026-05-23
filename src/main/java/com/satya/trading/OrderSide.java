package com.satya.trading;

// my comment
public enum OrderSide {
    BUY, SELL;

    public String api() {
        return name().toLowerCase();
    }
}
