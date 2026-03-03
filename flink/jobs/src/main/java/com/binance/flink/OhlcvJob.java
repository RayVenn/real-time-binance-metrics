package com.binance.flink;

import com.binance.flink.function.ArbitrageDetector;
import com.binance.flink.function.EmaFunction;
import com.binance.flink.function.OhlcvAggregator;
import com.binance.flink.function.OhlcvWindowFunction;
import com.binance.flink.model.BinanceTrade;
import com.binance.flink.model.OhlcvBar;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;

import java.sql.Timestamp;
import java.time.Duration;

public class OhlcvJob {

    /**
     * Normalize symbol so trades from different exchanges combine into one key.
     * e.g. BTCUSDT (Binance) and BTC-USD (Coinbase) both become BTCUSD.
     */
    private static String canonicalSymbol(String symbol) {
        // Strip trailing T from USDT → USDC
        if (symbol.endsWith("USDT")) symbol = symbol.substring(0, symbol.length() - 1);
        // Remove hyphens: BTC-USD → BTCUSD
        return symbol.replace("-", "");
    }

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.enableCheckpointing(10_000);
        env.setBufferTimeout(100);

        String bootstrapServers = System.getenv().getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
        String kafkaTopic = System.getenv().getOrDefault(
                "KAFKA_TOPIC", "crypto-trades");
        String timescaledbUrl = System.getenv().getOrDefault(
                "TIMESCALEDB_URL", "jdbc:postgresql://timescaledb:5432/binance");

        JdbcConnectionOptions jdbcOpts = new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                .withUrl(timescaledbUrl)
                .withDriverName("org.postgresql.Driver")
                .withUsername("postgres")
                .withPassword("password")
                .build();

        // ── Source ────────────────────────────────────────────────────────────
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(kafkaTopic)
                .setGroupId("flink-ohlcv")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> rawStream = env.fromSource(
                kafkaSource,
                WatermarkStrategy.noWatermarks(),
                kafkaTopic);

        // ── Parse JSON ────────────────────────────────────────────────────────
        ObjectMapper mapper = new ObjectMapper();
        DataStream<BinanceTrade> trades = rawStream.map(
                json -> mapper.readValue(json, BinanceTrade.class));

        // ── Watermark strategy ────────────────────────────────────────────────
        WatermarkStrategy<BinanceTrade> watermarkStrategy = WatermarkStrategy
                .<BinanceTrade>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((trade, ts) -> trade.tradeTimeMs);

        DataStream<BinanceTrade> timedTrades =
                trades.assignTimestampsAndWatermarks(watermarkStrategy);

        // ── Per-exchange OHLCV keyed by canonical symbol + source ─────────────
        DataStream<OhlcvBar> perExchangeOhlcv = timedTrades
                .keyBy(t -> canonicalSymbol(t.symbol) + "~" + t.source)
                .window(TumblingEventTimeWindows.of(Duration.ofMinutes(1)))
                .aggregate(new OhlcvAggregator(), new OhlcvWindowFunction())
                .keyBy(bar -> bar.symbol + "~" + bar.source)
                .process(new EmaFunction());

        // ── Cross-exchange spread detection (BINANCE, COINBASE, KRAKEN) ──────
        // Collect all 3 sources per window; emit when all arrive with spread populated
        DataStream<OhlcvBar> spreadBars = perExchangeOhlcv
                .keyBy(bar -> bar.symbol)
                .process(new ArbitrageDetector());

        // ── Sink 2: Write matched pairs to ohlcv (spread included) ───────────
        spreadBars.addSink(JdbcSink.sink(
                "INSERT INTO ohlcv (symbol, source, window_start, open, high, low, close, volume, vwap, buy_volume, sell_volume, ema10, trade_count, spread_usd, spread_bps) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (symbol, source, window_start) "
                        + "DO UPDATE SET spread_usd = EXCLUDED.spread_usd, spread_bps = EXCLUDED.spread_bps",
                (ps, bar) -> {
                    ps.setString(1, bar.symbol);
                    ps.setString(2, bar.source);
                    ps.setTimestamp(3, new Timestamp(bar.windowStartMs));
                    ps.setDouble(4, bar.open);
                    ps.setDouble(5, bar.high);
                    ps.setDouble(6, bar.low);
                    ps.setDouble(7, bar.close);
                    ps.setDouble(8, bar.volume);
                    ps.setDouble(9, bar.vwap);
                    ps.setDouble(10, bar.buyVolume);
                    ps.setDouble(11, bar.sellVolume);
                    ps.setDouble(12, bar.ema10);
                    ps.setInt(13, bar.tradeCount);
                    ps.setDouble(14, bar.spreadUsd);
                    ps.setDouble(15, bar.spreadBps);
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(50)
                        .withBatchIntervalMs(500)
                        .withMaxRetries(5)
                        .build(),
                jdbcOpts
        ));

        env.execute("ohlcv-1m");
    }
}
