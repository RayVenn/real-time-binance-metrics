package com.binance.flink.model;

import java.io.Serializable;

/**
 * Mutable accumulator for the incremental OHLCV aggregation.
 * Flink checkpoints this object — must be Serializable.
 */
public class OhlcvAccumulator implements Serializable {
    public double  open, high, low, close, volume;
    public int     count;
    public boolean initialized = false;
}
