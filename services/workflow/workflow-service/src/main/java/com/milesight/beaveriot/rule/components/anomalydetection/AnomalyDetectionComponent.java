package com.milesight.beaveriot.rule.components.anomalydetection;

import com.milesight.beaveriot.context.api.EntityServiceProvider;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.integration.model.AttributeBuilder;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.base.page.GenericPageRequest;
import com.milesight.beaveriot.base.page.Sorts;
import com.milesight.beaveriot.entity.model.response.EntityHistoryResponse;
import com.milesight.beaveriot.entity.service.EntityValueService;
import com.milesight.beaveriot.rule.annotations.OutputArguments;
import com.milesight.beaveriot.rule.annotations.RuleNode;
import com.milesight.beaveriot.rule.annotations.UriParamExtension;
import com.milesight.beaveriot.rule.api.ProcessorNode;
import com.milesight.beaveriot.rule.constants.RuleNodeType;
import com.milesight.beaveriot.rule.support.SpELExpressionHelper;
import com.milesight.beaveriot.rule.util.WorkflowEntityHelper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.spi.UriParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Statistical anomaly detector. For each selected entity it computes the mean and standard
 * deviation of its history over a baseline window (in Java, deterministically - no LLM), then
 * flags the entity when its latest value sits more than {@code sigmaThreshold} standard
 * deviations from that mean.
 * <p>
 * Detection is intentionally numeric and hallucination-free; a downstream LLM node should only
 * <em>narrate</em> the {@code summary} this node produces, never decide what counts as anomalous.
 * Outputs {@code hasAnomaly} (branch on it), {@code anomalyCount}, and a plain-text {@code summary}.
 */
@Slf4j
@RuleNode(value = "anomalyDetection", type = RuleNodeType.ACTION, description = "Anomaly Detection", testable = false)
@Data
public class AnomalyDetectionComponent implements ProcessorNode<Exchange> {

    /** Minimum history points needed before a baseline is considered meaningful. */
    private static final int MIN_BASELINE_POINTS = 5;

    @OutputArguments
    @UriParam(javaType = "java.util.List", prefix = "bean", displayName = "Entities")
    @UriParamExtension(uiComponent = "entityMultipleSelect")
    private List<String> entities;

    @UriParam(javaType = "integer", prefix = "bean", displayName = "Baseline Minutes",
            defaultValue = "10080",
            description = "How much history to use as the 'normal' baseline, in minutes. 10080 = last 7 days.")
    private Integer baselineMinutes;

    @UriParam(javaType = "number", prefix = "bean", displayName = "Sigma Threshold",
            defaultValue = "3.0",
            description = "How many standard deviations from the baseline mean counts as an anomaly. 3.0 is typical.")
    private Double sigmaThreshold;

    @Autowired
    private EntityServiceProvider entityServiceProvider;

    @Autowired
    private EntityValueServiceProvider entityValueServiceProvider;

    @Autowired
    private EntityValueService entityValueService;

    @Autowired
    private WorkflowEntityHelper workflowEntityHelper;

    @Override
    public void processor(Exchange exchange) {
        List<String> entityKeys = SpELExpressionHelper.resolveExpression(exchange, entities);

        List<String> anomalyLines = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();

        if (!CollectionUtils.isEmpty(entityKeys)) {
            workflowEntityHelper.checkEntityExist(entityKeys);

            long end = System.currentTimeMillis();
            long start = end - resolveBaselineMinutes() * 60_000L;
            double threshold = resolveSigmaThreshold();

            for (String entityKey : entityKeys) {
                Map<String, Object> detail = evaluateEntity(entityKey, start, end, threshold);
                if (detail != null) {
                    details.add(detail);
                    if (Boolean.TRUE.equals(detail.get("anomaly"))) {
                        anomalyLines.add(String.valueOf(detail.get("description")));
                    }
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hasAnomaly", !anomalyLines.isEmpty());
        body.put("anomalyCount", anomalyLines.size());
        body.put("summary", anomalyLines.isEmpty()
                ? "No anomalies detected."
                : String.join("; ", anomalyLines));
        body.put("details", details);

        exchange.getIn().setBody(body);
    }

    private Map<String, Object> evaluateEntity(String entityKey, long start, long end, double threshold) {
        Entity entity = entityServiceProvider.findByKey(entityKey);
        if (entity == null || entity.getId() == null) {
            log.warn("Anomaly detection: entity '{}' not found, skipping", entityKey);
            return null;
        }

        Double current = toDouble(entityValueServiceProvider.findValueByKey(entityKey));
        if (current == null) {
            return null;
        }

        List<Double> baseline = fetchBaselineValues(entity.getId(), start, end);
        if (baseline.size() < MIN_BASELINE_POINTS) {
            return null;
        }

        double mean = baseline.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = baseline.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        double stdDev = Math.sqrt(variance);

        double zScore = stdDev > 0 ? (current - mean) / stdDev : 0;
        boolean anomaly = stdDev > 0 && Math.abs(zScore) >= threshold;

        String unit = entity.getAttributeStringValue(AttributeBuilder.ATTRIBUTE_UNIT);
        String unitStr = unit != null ? unit : "";

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", entity.getName());
        detail.put("current", current);
        detail.put("mean", round(mean));
        detail.put("stdDev", round(stdDev));
        detail.put("zScore", round(zScore));
        detail.put("anomaly", anomaly);
        if (anomaly) {
            String direction = zScore > 0 ? "above" : "below";
            detail.put("description", String.format(
                    "%s is %s%s, %.1f sigma %s its baseline average of %s%s",
                    entity.getName(), formatValue(current), unitStr,
                    Math.abs(zScore), direction, formatValue(round(mean)), unitStr));
        }
        return detail;
    }

    private List<Double> fetchBaselineValues(Long entityId, long start, long end) {
        try {
            GenericPageRequest pageRequest = new GenericPageRequest();
            pageRequest.setPageNumber(1);
            pageRequest.sort(new Sorts().desc("timestamp"));
            List<EntityHistoryResponse> series =
                    entityValueService.historySearchSlice(List.of(entityId), start, end, pageRequest);
            List<Double> values = new ArrayList<>();
            for (EntityHistoryResponse point : series) {
                Double v = toDouble(point.getValue());
                if (v != null) {
                    values.add(v);
                }
            }
            return values;
        } catch (Exception e) {
            log.warn("Anomaly detection: failed to load history for entity {}", entityId, e);
            return List.of();
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String formatValue(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private int resolveBaselineMinutes() {
        return baselineMinutes == null || baselineMinutes <= 0 ? 10080 : baselineMinutes;
    }

    private double resolveSigmaThreshold() {
        return sigmaThreshold == null || sigmaThreshold <= 0 ? 3.0 : sigmaThreshold;
    }

}
