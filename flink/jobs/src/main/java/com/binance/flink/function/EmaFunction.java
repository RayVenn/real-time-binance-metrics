package com.binance.flink.function;

import com.binance.flink.model.OhlcvBar;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * Computes a 10-period Exponential Moving Average (EMA) over the close price
 * of consecutive 1-minute OHLCV bars, keyed by symbol~source.
 *
 * Formula:  EMA_t = α * close_t + (1 - α) * EMA_{t-1}
 *           where α = 2 / (N + 1) = 2 / 11 ≈ 0.1818
 *
 * The first bar seeds EMA with its own close price (cold-start).
 * Flink checkpoints emaState so the running EMA survives job restarts.
 */
public class EmaFunction extends KeyedProcessFunction<String, OhlcvBar, OhlcvBar> {

    private static final int    PERIOD = 10;
    private static final double ALPHA  = 2.0 / (PERIOD + 1); // ≈ 0.1818

    private ValueState<Double> emaState;

    @Override
    public void open(Configuration parameters) {
        emaState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("ema10", Double.class));
    }

    @Override
    public void processElement(OhlcvBar bar,
                               Context ctx,
                               Collector<OhlcvBar> out) throws Exception {
        Double prev = emaState.value();
        double ema  = (prev == null) ? bar.close : ALPHA * bar.close + (1 - ALPHA) * prev;
        emaState.update(ema);
        bar.ema10 = ema;
        out.collect(bar);
    }
}
