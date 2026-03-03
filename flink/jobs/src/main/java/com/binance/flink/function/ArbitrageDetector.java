package com.binance.flink.function;

import com.binance.flink.model.OhlcvBar;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.Set;

/**
 * Collects 1-minute OHLCV bars from all exchanges (BINANCE, COINBASE, KRAKEN)
 * for the same symbol + window, then emits all bars annotated with:
 *   spread_usd = max(close) − min(close)
 *   spread_bps = spread_usd / avg(close) * 10_000
 */
public class ArbitrageDetector extends KeyedProcessFunction<String, OhlcvBar, OhlcvBar> {

    private static final Set<String> EXCHANGES = Set.of("BINANCE", "COINBASE", "KRAKEN");

    private MapState<Long, OhlcvBar> binanceState;
    private MapState<Long, OhlcvBar> coinbaseState;
    private MapState<Long, OhlcvBar> krakenState;

    @Override
    public void open(Configuration cfg) {
        binanceState  = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("binance-bars",  Types.LONG, Types.POJO(OhlcvBar.class)));
        coinbaseState = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("coinbase-bars", Types.LONG, Types.POJO(OhlcvBar.class)));
        krakenState   = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("kraken-bars",   Types.LONG, Types.POJO(OhlcvBar.class)));
    }

    @Override
    public void processElement(OhlcvBar bar, Context ctx, Collector<OhlcvBar> out) throws Exception {
        switch (bar.source) {
            case "BINANCE"  -> binanceState.put(bar.windowStartMs, bar);
            case "COINBASE" -> coinbaseState.put(bar.windowStartMs, bar);
            case "KRAKEN"   -> krakenState.put(bar.windowStartMs, bar);
        }

        OhlcvBar binance  = binanceState.get(bar.windowStartMs);
        OhlcvBar coinbase = coinbaseState.get(bar.windowStartMs);
        OhlcvBar kraken   = krakenState.get(bar.windowStartMs);

        if (binance != null && coinbase != null && kraken != null) {
            emitAll(binance, coinbase, kraken, out);
            binanceState.remove(bar.windowStartMs);
            coinbaseState.remove(bar.windowStartMs);
            krakenState.remove(bar.windowStartMs);
        }
    }

    private void emitAll(OhlcvBar binance, OhlcvBar coinbase, OhlcvBar kraken,
                         Collector<OhlcvBar> out) {
        double maxClose  = Math.max(binance.close, Math.max(coinbase.close, kraken.close));
        double minClose  = Math.min(binance.close, Math.min(coinbase.close, kraken.close));
        double avgClose  = (binance.close + coinbase.close + kraken.close) / 3.0;
        double spreadUsd = maxClose - minClose;
        double spreadBps = spreadUsd / avgClose * 10_000.0;

        binance.spreadUsd  = spreadUsd;  binance.spreadBps  = spreadBps;
        coinbase.spreadUsd = spreadUsd;  coinbase.spreadBps = spreadBps;
        kraken.spreadUsd   = spreadUsd;  kraken.spreadBps   = spreadBps;

        out.collect(binance);
        out.collect(coinbase);
        out.collect(kraken);
    }
}
