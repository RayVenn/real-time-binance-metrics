package com.marketdata.flink.model;

import java.io.Serializable;

/**
 * Final 1-minute OHLCV bar emitted downstream to ClickHouse and stdout.
 */
public class OhlcvBar implements Serializable {
    public String symbol;
    public String source;
    public long   windowStartMs;
    public long   windowEndMs;
    public double open, high, low, close, volume;
    public double vwap;
    public double buyVolume;
    public double sellVolume;
    public double ema10;
    public int    tradeCount;
    public double spreadUsd;  // binanceClose - coinbaseClose  (0 if not yet matched)
    public double spreadBps;  // abs(spreadUsd) / midPrice * 10_000  (0 if not yet matched)
}
