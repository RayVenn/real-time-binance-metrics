package com.binance.flink;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class OhlcvJob {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /**
     * Normalize symbol so trades from different exchanges combine into one key.
     * e.g. BTCUSDT (Binance) and BTCUSD (Coinbase) both become BTCUSD.
     */
    private static String canonicalSymbol(String symbol) {
        return symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 1) : symbol;
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
        String clickhouseUrl = System.getenv().getOrDefault(
                "CLICKHOUSE_URL", "jdbc:clickhouse://clickhouse:8123/binance");

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

        // ── Watermark strategy (assigned once; both streams share it) ─────────
        WatermarkStrategy<BinanceTrade> watermarkStrategy = WatermarkStrategy
                .<BinanceTrade>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((trade, ts) -> trade.tradeTimeMs);

        DataStream<BinanceTrade> timedTrades =
                trades.assignTimestampsAndWatermarks(watermarkStrategy);

        // ── Window + aggregate — all exchanges merged, keyed by canonical symbol ─
        // Trades from all exchanges are combined in event-time order so open/close
        // are truly cross-exchange accurate. source is set to "ALL" by the key format.
        DataStream<OhlcvBar> ohlcv = timedTrades
                .keyBy(t -> canonicalSymbol(t.symbol) + "~ALL")
                .window(TumblingEventTimeWindows.of(Duration.ofMinutes(1)))
                .aggregate(new OhlcvAggregator(), new OhlcvWindowFunction());

        // ── Sink 1: ClickHouse via JDBC ───────────────────────────────────────
        ohlcv.addSink(JdbcSink.sink(
                "INSERT INTO ohlcv (symbol, source, window_start, open, high, low, close, volume, trade_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (ps, bar) -> {
                    ps.setString(1, bar.symbol);
                    ps.setString(2, bar.source);
                    ps.setTimestamp(3, new Timestamp(bar.windowStartMs));
                    ps.setDouble(4, bar.open);
                    ps.setDouble(5, bar.high);
                    ps.setDouble(6, bar.low);
                    ps.setDouble(7, bar.close);
                    ps.setDouble(8, bar.volume);
                    ps.setInt(9, bar.tradeCount);
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(100)
                        .withBatchIntervalMs(200)
                        .withMaxRetries(5)
                        .build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(clickhouseUrl)
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername("default")
                        .withPassword("")
                        .build()
        ));

        // ── Sink 2: stdout ────────────────────────────────────────────────────
        ohlcv.map(bar -> String.format(
                "%s | %s | %s UTC | O=%10.2f  H=%10.2f  L=%10.2f  C=%10.2f  V=%10.5f  n=%d",
                bar.source, bar.symbol,
                FMT.format(Instant.ofEpochMilli(bar.windowStartMs)),
                bar.open, bar.high, bar.low, bar.close, bar.volume, bar.tradeCount
        )).print();

        env.execute("ohlcv-1m");
    }
}
