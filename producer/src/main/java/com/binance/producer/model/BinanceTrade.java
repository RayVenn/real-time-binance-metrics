package com.binance.producer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Binance trade event from the WebSocket combined stream.
 * Field names match the Binance wire format for deserialization,
 * and the output JSON uses snake_case for Kafka compatibility with downstream consumers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceTrade {

    @JsonProperty("e") public String  eventType;
    @JsonProperty("E") public long    eventTimeMs;
    @JsonProperty("s") public String  symbol;
    @JsonProperty("t") public long    tradeId;
    @JsonProperty("p") public double  price;
    @JsonProperty("q") public double  quantity;
    @JsonProperty("T") public long    tradeTimeMs;
    @JsonProperty("m") public boolean isBuyerMaker;

    // Set by the producer — not in the Binance payload
    public long ingestionTimeMs;
    public long latencyMs;

    public void enrich() {
        this.ingestionTimeMs = System.currentTimeMillis();
        this.latencyMs       = this.ingestionTimeMs - this.tradeTimeMs;
    }
}
