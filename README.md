# Binance Real-Time Metrics

Real-time OHLCV pipeline for Binance trade streams — from WebSocket to ClickHouse in under 1 second.

## Architecture

```
Binance WebSocket API
        ↓
Java Producer  (parse · enrich · ingestion timestamp)
        ↓
Kafka (KRaft, no ZooKeeper)
  ├── binance-trades        (3 partitions, 24 h retention)
  └── binance-trades-dlq    (failed / invalid messages)
        ↓
Apache Flink  (1-minute tumbling windows, event-time watermarks)
        ↓
ClickHouse  (MergeTree, partitioned by month)
        ↓
Grafana Dashboard  (candlestick + volume, ClickHouse plugin)
```

## Tech Stack

| Component         | Technology                          |
|-------------------|-------------------------------------|
| Data source       | Binance WebSocket combined stream   |
| Message broker    | Apache Kafka 7.6 (KRaft mode)       |
| Stream processing | Apache Flink 1.19 (Java)            |
| Time-series DB    | ClickHouse 24.3                     |
| Visualisation     | Grafana 10.4                        |
| Build tool        | Gradle 9 (multi-project)            |
| Runtime           | Java 25 (producer) / Java 17 (Flink)|

---

## Quick Start

### Prerequisites
- Docker + Docker Compose v2
- Internet access (Binance WebSocket is public, no API key required)

### Run the core pipeline

```bash
# Start Kafka + producer + Flink + ClickHouse
docker compose up --build -d

# With Grafana dashboard
docker compose --profile monitoring up --build -d
```

### Verify data is flowing

```bash
# Producer health
curl http://localhost:8080/health
# → {"status":"ok","produced":1234,"dlq":0,"wsConnected":true}

# Watch live trade messages from Kafka
docker compose exec kafka \
  kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic binance-trades

# Query ClickHouse for OHLCV bars
docker compose exec clickhouse \
  clickhouse-client --query \
  "SELECT symbol, window_start, open, high, low, close, volume, trade_count
   FROM binance.ohlcv
   ORDER BY window_start DESC
   LIMIT 10"

# Flink job status (REST API)
curl http://localhost:8081/jobs
```

### Grafana

Open [http://localhost:3000](http://localhost:3000) (admin / admin) — the ClickHouse datasource and OHLCV dashboard are auto-provisioned.

---

## Configuration

All producer settings are environment variables (see [.env.example](.env.example)).

| Variable                  | Default                                     | Description                          |
|---------------------------|---------------------------------------------|--------------------------------------|
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092`                               | Kafka broker address                 |
| `KAFKA_TOPIC`             | `binance-trades`                            | Main topic                           |
| `KAFKA_DLQ_TOPIC`         | `binance-trades-dlq`                        | Dead-letter queue topic              |
| `SYMBOLS`                 | `BTCUSDT`                                   | Comma-separated pairs, e.g. `BTCUSDT,ETHUSDT` |
| `BINANCE_WS_URL`          | `wss://data-stream.binance.vision/stream`   | Binance combined stream base URL     |
| `RECONNECT_DELAY_S`       | `5`                                         | Initial WebSocket reconnect delay (s)|
| `MAX_RECONNECT_DELAY_S`   | `60`                                        | Max reconnect delay after backoff (s)|
| `HEALTH_PORT`             | `8080`                                      | Health check HTTP port               |
| `CLICKHOUSE_URL`          | `jdbc:clickhouse://clickhouse:8123/binance` | ClickHouse JDBC URL (Flink job)      |

---

## Message Schema

Each message on `binance-trades` uses Binance wire-format keys:

```json
{
  "e": "trade",
  "E": 1709000000000,
  "s": "BTCUSDT",
  "t": 3456789,
  "p": 62345.67,
  "q": 0.012,
  "T": 1709000000000,
  "m": false,
  "ingestionTimeMs": 1709000000042,
  "latencyMs": 42
}
```

Failed messages on `binance-trades-dlq`:

```json
{
  "rawPayload": "...",
  "error": "...",
  "ingestionTimeMs": 1709000000000
}
```

---

## Project Structure

```
.
├── settings.gradle                        # root multi-project build
├── build.gradle                           # shared conventions (Java 25)
├── Dockerfile                             # producer multi-stage image
├── docker-compose.yml
├── producer/
│   ├── build.gradle
│   └── src/main/java/com/binance/producer/
│       ├── Main.java
│       ├── Config.java                    # env-var config
│       ├── model/BinanceTrade.java
│       ├── model/DlqMessage.java
│       ├── ws/BinanceWebSocketClient.java # exponential backoff reconnect
│       ├── kafka/KafkaProducerClient.java
│       └── health/HealthServer.java       # GET /health → JSON
├── flink/jobs/
│   ├── build.gradle                       # shadow JAR, Java 17 target
│   └── src/main/java/com/binance/flink/
│       ├── OhlcvJob.java                  # main pipeline
│       ├── model/BinanceTrade.java
│       ├── model/OhlcvBar.java
│       ├── model/OhlcvAccumulator.java
│       ├── function/OhlcvAggregator.java
│       └── function/OhlcvWindowFunction.java
├── clickhouse/
│   ├── init.sql                           # database + ohlcv table
│   └── users.xml                          # allow Docker network connections
└── grafana/
    └── provisioning/
        ├── datasources/clickhouse.yaml
        └── dashboards/ohlcv.json
```

---

## Local Build

```bash
# Build producer JAR
./gradlew :producer:jar

# Build Flink fat JAR
./gradlew :flink-jobs:shadowJar
```
