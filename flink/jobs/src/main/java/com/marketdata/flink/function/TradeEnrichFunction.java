package com.marketdata.flink.function;

import com.marketdata.flink.model.BinanceTrade;
import com.marketdata.flink.model.EnrichedTrade;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * Enriches each raw trade with a human-readable side ("BUY"/"SELL") and emits
 * immediately — no windowing or state required.
 *
 * Keyed by symbol~source so records for the same stream are routed to the same
 * task slot, which preserves event-time ordering within each key partition.
 *
 * isBuyerMaker=true → the buyer is the passive market maker → the aggressive
 * side is the seller → side = "SELL".
 */
public class TradeEnrichFunction
        extends KeyedProcessFunction<String, BinanceTrade, EnrichedTrade> {

    @Override
    public void processElement(BinanceTrade t, Context ctx, Collector<EnrichedTrade> out) {
        EnrichedTrade e   = new EnrichedTrade();
        e.symbol          = t.symbol;
        e.source          = t.source;
        e.tradeId         = t.tradeId;
        e.price           = t.price;
        e.quantity        = t.quantity;
        e.side            = t.isBuyerMaker ? "SELL" : "BUY";
        e.tradeTimeMs     = t.tradeTimeMs;
        e.ingestionTimeMs = t.ingestionTimeMs;
        e.latencyMs       = t.latencyMs;
        out.collect(e);
    }
}
