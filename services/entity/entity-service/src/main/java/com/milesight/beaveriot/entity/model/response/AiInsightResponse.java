package com.milesight.beaveriot.entity.model.response;

import lombok.Data;

/**
 * @author leon
 */
@Data
public class AiInsightResponse {

    public enum Trend {
        UP, DOWN, FLAT
    }

    private String summary;

    private Trend trend;

    private long generatedAt;

    private boolean cached;

}
