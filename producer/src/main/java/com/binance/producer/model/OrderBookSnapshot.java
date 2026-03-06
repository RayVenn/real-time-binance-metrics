package com.binance.producer.model;

import java.util.List;

public class OrderBookSnapshot {
    public String          source;       // "BINANCE", "COINBASE", "KRAKEN"
    public String          symbol;       // canonical, e.g. "BTCUSDT"
    public long            timestampMs;
    public List<PriceLevel> bids;
    public List<PriceLevel> asks;

    public static class PriceLevel {
        public double price;
        public double quantity;

        public PriceLevel() {}

        public PriceLevel(double price, double quantity) {
            this.price    = price;
            this.quantity = quantity;
        }
    }
}
