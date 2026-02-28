package com.binance.flink.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Kafka message schema produced by the Java producer.
 * Field names match the producer's Jackson output (camelCase from Java fields).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceTrade implements Serializable {

    // Wire-format names from the producer's @JsonProperty annotations
    @JsonProperty("s") public String  symbol;
    @JsonProperty("p") public double  price;
    @JsonProperty("q") public double  quantity;
    @JsonProperty("T") public long    tradeTimeMs;
    @JsonProperty("m") public boolean isBuyerMaker;
    @JsonProperty("e") public String  eventType;
    @JsonProperty("E") public long    eventTimeMs;
    @JsonProperty("t") public long    tradeId;

    // Added by producer — use field names directly
    public String source;
    public long   ingestionTimeMs;
    public long   latencyMs;
}
