package com.milesight.beaveriot.entity.model.request;

import com.milesight.beaveriot.entity.enums.AggregateType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @author leon
 */
@Data
public class AiInsightQuery {

    @NotEmpty
    private List<String> entityKeys;

    @NotNull
    private Long startTimestamp;

    @NotNull
    private Long endTimestamp;

    private AggregateType aggregateType = AggregateType.AVG;

    /**
     * When true, the same window immediately preceding [startTimestamp, endTimestamp)
     * is queried too, so the summary can call out a trend/delta.
     */
    private boolean compareToPreviousPeriod;

    /**
     * How long a generated insight may be served from cache before it's regenerated,
     * driven by the widget's own configured refresh cadence.
     */
    private Integer cacheTtlSeconds = 3600;

    /**
     * Optional user-supplied instruction that steers what the summary should focus on
     * (e.g. "flag readings outside a comfortable indoor range"). Appended to the
     * generated stats prompt; the model still narrates only the numbers it is given.
     */
    private String customPrompt;

}
