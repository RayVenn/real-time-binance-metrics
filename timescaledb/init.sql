CREATE TABLE IF NOT EXISTS ohlcv (
    symbol       VARCHAR(20)       NOT NULL,
    source       VARCHAR(20)       NOT NULL,
    window_start TIMESTAMPTZ       NOT NULL,
    open         DOUBLE PRECISION  NOT NULL,
    high         DOUBLE PRECISION  NOT NULL,
    low          DOUBLE PRECISION  NOT NULL,
    close        DOUBLE PRECISION  NOT NULL,
    volume       DOUBLE PRECISION  NOT NULL,
    vwap         DOUBLE PRECISION  NOT NULL,
    buy_volume   DOUBLE PRECISION  NOT NULL,
    sell_volume  DOUBLE PRECISION  NOT NULL,
    ema10        DOUBLE PRECISION  NOT NULL,
    trade_count  INTEGER           NOT NULL,
    spread_usd   DOUBLE PRECISION,
    spread_bps   DOUBLE PRECISION,
    PRIMARY KEY (symbol, source, window_start)
);

SELECT create_hypertable('ohlcv', 'window_start', if_not_exists => TRUE);

CREATE TABLE IF NOT EXISTS trades (
    trade_time       TIMESTAMPTZ       NOT NULL,
    symbol           VARCHAR(20)       NOT NULL,
    source           VARCHAR(20)       NOT NULL,
    trade_id         BIGINT            NOT NULL,
    price            DOUBLE PRECISION  NOT NULL,
    quantity         DOUBLE PRECISION  NOT NULL,
    side             VARCHAR(4)        NOT NULL,
    ingestion_time   TIMESTAMPTZ       NOT NULL,
    latency_ms       BIGINT            NOT NULL,
    PRIMARY KEY (symbol, source, trade_id, trade_time)
);

SELECT create_hypertable('trades', 'trade_time', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS trades_symbol_time_idx
    ON trades (symbol, trade_time DESC);

CREATE TABLE IF NOT EXISTS orderbook_snapshots (
    snapshot_time    TIMESTAMPTZ       NOT NULL,
    symbol           VARCHAR(20)       NOT NULL,
    source           VARCHAR(20)       NOT NULL,
    bids             JSONB             NOT NULL,
    asks             JSONB             NOT NULL,
    PRIMARY KEY (symbol, source, snapshot_time)
);

SELECT create_hypertable('orderbook_snapshots', 'snapshot_time', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS orderbook_latest_idx
    ON orderbook_snapshots (symbol, source, snapshot_time DESC);
