package com.marketdata.flink.model;

import java.io.Serializable;

public class EnrichedTrade implements Serializable {
    public String symbol;
    public String source;
    public long   tradeId;
    public double price;
    public double quantity;
    public String side;           // "BUY" or "SELL"
    public long   tradeTimeMs;
    public long   ingestionTimeMs;
    public long   latencyMs;
}
