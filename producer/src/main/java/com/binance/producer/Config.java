package com.binance.producer;

import java.util.Arrays;
import java.util.List;

public class Config {
    // ── Kafka (shared) ────────────────────────────────────────────────────────
    public final String bootstrapServers     = env("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
    public final String kafkaTopic           = env("KAFKA_TOPIC",             "crypto-trades");
    public final String kafkaDlqTopic        = env("KAFKA_DLQ_TOPIC",         "crypto-trades-dlq");
    public final String kafkaOrderBookTopic  = env("KAFKA_ORDERBOOK_TOPIC",   "crypto-orderbook");
    public final int    kafkaFlushEvery      = Integer.parseInt(env("KAFKA_FLUSH_EVERY", "100"));

    // ── Reconnection (shared) ─────────────────────────────────────────────────
    public final double reconnectDelaySec    = Double.parseDouble(env("RECONNECT_DELAY_S",     "5.0"));
    public final double maxReconnectDelaySec = Double.parseDouble(env("MAX_RECONNECT_DELAY_S", "60.0"));

    // ── Binance ───────────────────────────────────────────────────────────────
    public final String binanceWsUrl        = env("BINANCE_WS_URL",        "wss://data-stream.binance.vision/stream");
    public final String symbols             = env("SYMBOLS",               "BTCUSDT");
    public final String binanceDepthStreams = env("BINANCE_DEPTH_STREAMS", "btcusdt@depth20@100ms");
    public final int    healthPort          = Integer.parseInt(env("HEALTH_PORT", "8080"));

    // ── Coinbase ──────────────────────────────────────────────────────────────
    public final String coinbaseWsUrl      = env("COINBASE_WS_URL",      "wss://advanced-trade-ws.coinbase.com");
    public final String coinbaseSymbols    = env("COINBASE_SYMBOLS",     "BTC-USD");
    public final int    coinbaseHealthPort = Integer.parseInt(env("COINBASE_HEALTH_PORT", "8082"));

    // ── Kraken ────────────────────────────────────────────────────────────────
    public final String krakenWsUrl     = env("KRAKEN_WS_URL",     "wss://ws.kraken.com/v2");
    public final String krakenSymbols   = env("KRAKEN_SYMBOLS",    "BTC/USD");

    public List<String> symbolsList() {
        return Arrays.stream(symbols.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public List<String> coinbaseSymbolsList() {
        return Arrays.stream(coinbaseSymbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public List<String> krakenSymbolsList() {
        return Arrays.stream(krakenSymbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String env(String key, String defaultValue) {
        return System.getenv().getOrDefault(key, defaultValue);
    }
}
