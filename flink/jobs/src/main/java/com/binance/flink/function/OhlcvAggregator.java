package com.binance.flink.function;

import com.binance.flink.model.BinanceTrade;
import com.binance.flink.model.OhlcvAccumulator;
import com.binance.flink.model.OhlcvBar;
import org.apache.flink.api.common.functions.AggregateFunction;

/**
 * Incremental OHLCV aggregation over a 1-minute tumbling window.
 * AggregateFunction is more efficient than reduce because it updates
 * the accumulator one trade at a time without buffering all elements.
 */
public class OhlcvAggregator
        implements AggregateFunction<BinanceTrade, OhlcvAccumulator, OhlcvBar> {

    @Override
    public OhlcvAccumulator createAccumulator() {
        return new OhlcvAccumulator();
    }

    @Override
    public OhlcvAccumulator add(BinanceTrade trade, OhlcvAccumulator acc) {
        if (!acc.initialized) {
            acc.open        = trade.price;
            acc.high        = trade.price;
            acc.low         = trade.price;
            acc.initialized = true;
        } else {
            acc.high = Math.max(acc.high, trade.price);
            acc.low  = Math.min(acc.low,  trade.price);
        }
        acc.close  = trade.price;
        acc.volume += trade.quantity;
        acc.count++;
        return acc;
    }

    @Override
    public OhlcvBar getResult(OhlcvAccumulator acc) {
        OhlcvBar bar = new OhlcvBar();
        bar.open       = acc.open;
        bar.high       = acc.high;
        bar.low        = acc.low;
        bar.close      = acc.close;
        bar.volume     = acc.volume;
        bar.tradeCount = acc.count;
        // symbol and window timestamps are set by OhlcvWindowFunction
        return bar;
    }

    @Override
    public OhlcvAccumulator merge(OhlcvAccumulator a, OhlcvAccumulator b) {
        if (!a.initialized) return b;
        if (!b.initialized) return a;
        OhlcvAccumulator merged = new OhlcvAccumulator();
        merged.open        = a.open;
        merged.high        = Math.max(a.high, b.high);
        merged.low         = Math.min(a.low,  b.low);
        merged.close       = b.close;   // b is the later window segment
        merged.volume      = a.volume + b.volume;
        merged.count       = a.count   + b.count;
        merged.initialized = true;
        return merged;
    }
}
