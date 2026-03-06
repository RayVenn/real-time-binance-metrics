package com.marketdata.flink.model;

import java.io.Serializable;

/**
 * Mutable accumulator for the incremental OHLCV aggregation.
 * Flink checkpoints this object — must be Serializable.
 */
public class OhlcvAccumulator implements Serializable {
    public double  open, high, low, close, volume;
    public double  priceVolumeSum;   // for VWAP: sum(price * qty)
    public double  buyVolume;        // qty where buyer is aggressor (!isBuyerMaker)
    public double  sellVolume;       // qty where seller is aggressor (isBuyerMaker)
    public int     count;
    public boolean initialized = false;
}
