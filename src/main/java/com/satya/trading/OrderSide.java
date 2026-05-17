package com.satya.apca;

public enum OrderSide {
    BUY, SELL;

    public String api() {
        return name().toLowerCase();
    }
}
