package com.binance.flink.function;

import com.binance.flink.model.OhlcvBar;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/**
 * Attaches the symbol (key) and window timestamps to the aggregated OhlcvBar.
 * This runs after OhlcvAggregator and receives a single pre-aggregated bar.
 *
 * In Java Flink, output is emitted via out.collect() — not Python's yield.
 */
public class OhlcvWindowFunction
        extends ProcessWindowFunction<OhlcvBar, OhlcvBar, String, TimeWindow> {

    @Override
    public void process(String symbol,
                        Context ctx,
                        Iterable<OhlcvBar> elements,
                        Collector<OhlcvBar> out) {
        OhlcvBar bar = elements.iterator().next();
        // key format: "BTCUSDT~BINANCE"
        String[] parts    = symbol.split("~", 2);
        bar.symbol        = parts[0];
        bar.source        = parts.length > 1 ? parts[1] : "UNKNOWN";
        bar.windowStartMs = ctx.window().getStart();
        bar.windowEndMs   = ctx.window().getEnd();
        out.collect(bar);
    }
}
