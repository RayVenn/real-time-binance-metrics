package com.binance.producer;

import java.util.Arrays;
import java.util.List;

public class Config {
    public final String bootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
    public final String kafkaTopic       = env("KAFKA_TOPIC",             "binance-trades");
    public final String kafkaDlqTopic    = env("KAFKA_DLQ_TOPIC",         "binance-trades-dlq");
    public final String symbols          = env("SYMBOLS",                  "BTCUSDT");
    public final int    healthPort       = Integer.parseInt(env("HEALTH_PORT", "8080"));
    public final String binanceWsUrl     = env("BINANCE_WS_URL",
            "wss://data-stream.binance.vision/stream");
    public final double reconnectDelaySec = Double.parseDouble(env("RECONNECT_DELAY_S",  "5.0"));
    public final double maxReconnectDelaySec = Double.parseDouble(env("MAX_RECONNECT_DELAY_S", "60.0"));
    public final int    kafkaFlushEvery  = Integer.parseInt(env("KAFKA_FLUSH_EVERY", "100"));

    public List<String> symbolsList() {
        return Arrays.stream(symbols.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String env(String key, String defaultValue) {
        return System.getenv().getOrDefault(key, defaultValue);
    }
}
