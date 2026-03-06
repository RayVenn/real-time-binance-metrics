package com.marketdata.flink;

import com.marketdata.flink.function.TradeEnrichFunction;
import com.marketdata.flink.model.BinanceTrade;
import com.marketdata.flink.model.EnrichedTrade;
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

import java.sql.Timestamp;
import java.time.Duration;

public class TradeStreamJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.enableCheckpointing(10_000);
        env.setBufferTimeout(100);

        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
        String kafkaTopic       = System.getenv().getOrDefault("KAFKA_TOPIC",             "crypto-trades");
        String timescaledbUrl   = System.getenv().getOrDefault("TIMESCALEDB_URL",         "jdbc:postgresql://timescaledb:5432/binance");

        // ── Source — separate consumer group so OhlcvJob is unaffected ────────
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(kafkaTopic)
                .setGroupId("flink-trade-stream")
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

        // ── Watermarks on tradeTimeMs, 5s out-of-orderness tolerance ─────────
        WatermarkStrategy<BinanceTrade> watermarkStrategy = WatermarkStrategy
                .<BinanceTrade>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((trade, ts) -> trade.tradeTimeMs);

        DataStream<BinanceTrade> timedTrades =
                trades.assignTimestampsAndWatermarks(watermarkStrategy);

        // ── Enrich: add side label, emit in event-time order per key ─────────
        DataStream<EnrichedTrade> enriched = timedTrades
                .keyBy(t -> t.symbol + "~" + t.source)
                .process(new TradeEnrichFunction());

        // ── Sink → TimescaleDB trades hypertable ─────────────────────────────
        enriched.addSink(JdbcSink.sink(
                "INSERT INTO trades "
                        + "(trade_time, symbol, source, trade_id, price, quantity, side, ingestion_time, latency_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (symbol, source, trade_id, trade_time) DO NOTHING",
                (ps, t) -> {
                    ps.setTimestamp(1, new Timestamp(t.tradeTimeMs));
                    ps.setString(2,   t.symbol);
                    ps.setString(3,   t.source);
                    ps.setLong(4,     t.tradeId);
                    ps.setDouble(5,   t.price);
                    ps.setDouble(6,   t.quantity);
                    ps.setString(7,   t.side);
                    ps.setTimestamp(8, new Timestamp(t.ingestionTimeMs));
                    ps.setLong(9,     t.latencyMs);
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(100)
                        .withBatchIntervalMs(200)
                        .withMaxRetries(5)
                        .build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(timescaledbUrl)
                        .withDriverName("org.postgresql.Driver")
                        .withUsername("postgres")
                        .withPassword("password")
                        .build()
        ));

        env.execute("trade-stream");
    }
}
