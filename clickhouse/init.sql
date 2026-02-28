CREATE DATABASE IF NOT EXISTS binance;

CREATE TABLE IF NOT EXISTS binance.ohlcv
(
    symbol       LowCardinality(String),
    window_start DateTime64(3, 'UTC'),
    open         Float64,
    high         Float64,
    low          Float64,
    close        Float64,
    volume       Float64,
    trade_count  UInt32
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(window_start)
ORDER BY (symbol, window_start)
SETTINGS index_granularity = 8192;
