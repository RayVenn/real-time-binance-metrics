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
