package com.milesight.beaveriot.entity.service;

import com.milesight.beaveriot.base.utils.JsonUtils;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.integration.model.AttributeBuilder;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.entity.enums.AggregateType;
import com.milesight.beaveriot.entity.model.request.AiInsightQuery;
import com.milesight.beaveriot.entity.model.request.EntityAggregateQuery;
import com.milesight.beaveriot.entity.model.response.AiInsightResponse;
import com.milesight.beaveriot.entity.model.response.EntityAggregateResponse;
import com.milesight.beaveriot.eventbus.api.EventResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns pre-computed entity aggregate stats into a short plain-English summary via
 * an LLM completion service entity (currently the Ollama integration's
 * "Generate a completion" service, running a Qwen model).
 * <p>
 * All numeric aggregation happens here, in Java, against {@link EntityValueService#historyAggregate};
 * the model is only ever asked to narrate numbers it's given, never to compute them.
 *
 * @author leon
 */
@Slf4j
@Service
public class AiInsightService {

    @Autowired
    private EntityService entityService;

    @Autowired
    private EntityValueService entityValueService;

    @Autowired
    private EntityValueServiceProvider entityValueServiceProvider;

    /**
     * Entity key prefix of the LLM completion service entity to invoke. Defaults to the
     * bundled Ollama integration; point this at a different integration's service entity
     * to swap providers without touching this class.
     */
    @Value("${beaver-iot.ai-insight.service-entity-key-prefix:llm-integration.integration.generate_completion}")
    private String serviceEntityKeyPrefix;

    /**
     * Preferred model. Only used if the LLM integration's active provider actually offers it -
     * otherwise the first model the provider reports is used instead (see {@link #resolveModel()}).
     */
    @Value("${beaver-iot.ai-insight.model:qwen2.5}")
    private String model;

    /**
     * Entity key holding the comma-separated list of models available from the LLM
     * integration's currently-selected provider.
     */
    @Value("${beaver-iot.ai-insight.models-entity-key:llm-integration.integration.models}")
    private String modelsEntityKey;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AiInsightResponse generateInsight(AiInsightQuery query) {
        String cacheKey = buildCacheKey(query);
        CacheEntry cacheEntry = cache.get(cacheKey);
        if (cacheEntry != null && cacheEntry.expiresAt() > System.currentTimeMillis()) {
            AiInsightResponse cached = cacheEntry.response();
            AiInsightResponse response = JsonUtils.copy(cached);
            response.setCached(true);
            return response;
        }

        List<Map<String, Object>> stats = collectStats(query);
        AiInsightResponse response = stats.isEmpty() ? emptyResponse() : summarize(stats, query.getCustomPrompt());

        int ttlSeconds = query.getCacheTtlSeconds() == null ? 3600 : Math.max(query.getCacheTtlSeconds(), 60);
        cache.put(cacheKey, new CacheEntry(response, System.currentTimeMillis() + ttlSeconds * 1000L));
        return response;
    }

    private List<Map<String, Object>> collectStats(AiInsightQuery query) {
        List<Map<String, Object>> stats = new ArrayList<>();

        for (String entityKey : query.getEntityKeys()) {
            Entity entity = entityService.findByKey(entityKey);
            if (entity == null) {
                continue;
            }

            Double current = queryAggregateValue(entity.getId(), query.getAggregateType(),
                    query.getStartTimestamp(), query.getEndTimestamp());
            if (current == null) {
                continue;
            }

            Double previous = null;
            if (query.isCompareToPreviousPeriod()) {
                long windowLength = query.getEndTimestamp() - query.getStartTimestamp();
                previous = queryAggregateValue(entity.getId(), query.getAggregateType(),
                        query.getStartTimestamp() - windowLength, query.getStartTimestamp());
            }

            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("name", entity.getName());
            stat.put("unit", entity.getAttributeStringValue(AttributeBuilder.ATTRIBUTE_UNIT));
            stat.put("aggregateType", query.getAggregateType());
            stat.put("currentValue", current);
            if (previous != null) {
                stat.put("previousValue", previous);
            }
            stats.add(stat);
        }

        return stats;
    }

    private Double queryAggregateValue(Long entityId, AggregateType aggregateType, long start, long end) {
        EntityAggregateQuery aggregateQuery = new EntityAggregateQuery();
        aggregateQuery.setEntityId(entityId);
        aggregateQuery.setAggregateType(aggregateType);
        aggregateQuery.setStartTimestamp(start);
        aggregateQuery.setEndTimestamp(end);

        EntityAggregateResponse aggregateResponse = entityValueService.historyAggregate(aggregateQuery);
        Object value = aggregateResponse == null ? null : aggregateResponse.getValue();
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AiInsightResponse summarize(List<Map<String, Object>> stats, String customPrompt) {
        String prompt = formatStatsAsPrompt(stats);
        String sanitizedCustomPrompt = sanitizePromptText(customPrompt);
        if (!sanitizedCustomPrompt.isEmpty()) {
            prompt = prompt + " Focus of this summary: " + sanitizedCustomPrompt;
        }

        Map<String, Object> servicePayload = Map.of(
                serviceEntityKeyPrefix + ".model", resolveModel(),
                serviceEntityKeyPrefix + ".prompt", prompt,
                serviceEntityKeyPrefix + ".system", SYSTEM_PROMPT,
                serviceEntityKeyPrefix + ".format", "json"
        );

        EventResponse eventResponse;
        try {
            eventResponse = entityValueServiceProvider.saveValuesAndPublishSync(ExchangePayload.create(servicePayload));
        } catch (Exception e) {
            log.error("AI insight: LLM completion service call failed", e);
            return fallbackResponse(stats);
        }

        Object rawResponse = eventResponse == null ? null : eventResponse.get("response");
        if (!(rawResponse instanceof String responseText) || responseText.isBlank()) {
            log.warn("AI insight: LLM completion service returned no text, falling back to raw stats");
            return fallbackResponse(stats);
        }

        try {
            Map<String, Object> parsed = JsonUtils.toMap(responseText);
            AiInsightResponse response = new AiInsightResponse();
            response.setSummary(String.valueOf(parsed.get("summary")));
            response.setTrend(parseTrend(String.valueOf(parsed.get("trend"))));
            response.setGeneratedAt(System.currentTimeMillis());
            return response;
        } catch (Exception e) {
            log.warn("AI insight: model did not return valid JSON, using raw text as summary: {}", responseText);
            AiInsightResponse response = new AiInsightResponse();
            response.setSummary(responseText.trim());
            response.setTrend(AiInsightResponse.Trend.FLAT);
            response.setGeneratedAt(System.currentTimeMillis());
            return response;
        }
    }

    /**
     * Renders stats as a single plain-English line (stats separated by "; ") rather than
     * JSON or multiple lines, for the LLM prompt.
     * <p>
     * Do not change this back to JSON: some Ollama-compatible backends - notably Hailo's
     * "hailo-ollama" server (HailoRT GenAI SDK, used to run models on Hailo NPUs such as
     * the Hailo-10H) - fail with HAILO_INTERNAL_FAILURE("Failed to render prompt from
     * JSON strings") whenever the prompt text itself contains JSON syntax ('{', '}',
     * '[', ']', '"'), regardless of the "format" field. Plain text also works against
     * vanilla Ollama, so this format is the safe default across backends.
     * <p>
     * Also do not reintroduce newline characters ('\n') into the prompt: the same Hailo
     * backend fails the same way ("Failed to generate") whenever the prompt text contains
     * ANY newline, even a single trailing one, regardless of content. Newlines in the
     * "system" field are fine - only the "prompt" field is affected.
     */
    private String formatStatsAsPrompt(List<Map<String, Object>> stats) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> stat : stats) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            double currentValue = ((Number) stat.get("currentValue")).doubleValue();
            Object unit = stat.get("unit");
            String unitStr = unit != null ? unit.toString() : "";

            sb.append(stat.get("name"))
                    .append(" (").append(stat.get("aggregateType")).append("): ")
                    .append(formatValue(currentValue)).append(unitStr);

            Object previousValue = stat.get("previousValue");
            if (previousValue instanceof Number previousNumber) {
                sb.append(", previous period was ")
                        .append(formatValue(previousNumber.doubleValue())).append(unitStr);
            }
        }
        return sb.toString();
    }

    /**
     * Picks a model that the LLM integration's *currently selected* provider actually offers.
     * <p>
     * The configured {@code beaver-iot.ai-insight.model} is provider-specific (e.g. the Ollama
     * tag "qwen2.5:1.5b"), so it becomes invalid the moment the user switches the integration to
     * a cloud provider like OpenRouter - which then rejects the request and leaves the widget
     * showing only raw numbers. Prefer the configured model when the provider advertises it,
     * otherwise fall back to the provider's first available model.
     */
    private String resolveModel() {
        List<String> available = availableModels();
        if (available.isEmpty()) {
            return model;
        }
        return available.contains(model) ? model : available.get(0);
    }

    private List<String> availableModels() {
        try {
            Object value = entityValueServiceProvider.findValueByKey(modelsEntityKey);
            if (value == null || value.toString().isBlank()) {
                return List.of();
            }
            return Arrays.stream(value.toString().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            log.warn("AI insight: could not read available models from {}", modelsEntityKey, e);
            return List.of();
        }
    }

    /**
     * Cleans a user-supplied custom prompt so it's safe to embed in the "prompt" field
     * across LLM backends - in particular Hailo's "hailo-ollama" server, which fails on
     * any newline character and on JSON-structural characters ('{', '}', '[', ']', '"')
     * in the prompt text (see {@link #formatStatsAsPrompt}). Newlines and the structural
     * characters are replaced/removed rather than rejected so a user typing a multi-line
     * or quote-containing instruction still gets a usable result instead of an error.
     */
    private String sanitizePromptText(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text
                .replaceAll("[\\r\\n]+", " ")
                .replace('"', '\'')
                .replaceAll("[{}\\[\\]]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    private AiInsightResponse.Trend parseTrend(String value) {
        try {
            return AiInsightResponse.Trend.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return AiInsightResponse.Trend.FLAT;
        }
    }

    /**
     * Used when the LLM call fails or returns something unusable - a plain, un-narrated
     * readout of the same numbers rather than a blank widget.
     */
    private AiInsightResponse fallbackResponse(List<Map<String, Object>> stats) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> stat : stats) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            double currentValue = ((Number) stat.get("currentValue")).doubleValue();
            Object unit = stat.get("unit");
            sb.append(stat.get("name")).append(": ").append(formatValue(currentValue));
            if (unit != null) {
                sb.append(unit);
            }
            Object previousValue = stat.get("previousValue");
            if (previousValue instanceof Number previousNumber && previousNumber.doubleValue() != 0) {
                double deltaPercent = (currentValue - previousNumber.doubleValue()) / Math.abs(previousNumber.doubleValue()) * 100;
                sb.append(String.format(" (%+.1f%% vs previous period)", deltaPercent));
            }
        }

        AiInsightResponse response = new AiInsightResponse();
        response.setSummary(!sb.isEmpty() ? sb.toString() : "No data available for the selected period.");
        response.setTrend(AiInsightResponse.Trend.FLAT);
        response.setGeneratedAt(System.currentTimeMillis());
        return response;
    }

    private AiInsightResponse emptyResponse() {
        AiInsightResponse response = new AiInsightResponse();
        response.setSummary("Not enough data yet for the selected entities and period.");
        response.setTrend(AiInsightResponse.Trend.FLAT);
        response.setGeneratedAt(System.currentTimeMillis());
        return response;
    }

    private String formatValue(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private String buildCacheKey(AiInsightQuery query) {
        return String.join("|",
                String.join(",", query.getEntityKeys().stream().sorted().toList()),
                String.valueOf(query.getStartTimestamp()),
                String.valueOf(query.getEndTimestamp()),
                String.valueOf(query.getAggregateType()),
                String.valueOf(query.isCompareToPreviousPeriod()),
                sanitizePromptText(query.getCustomPrompt()));
    }

    private record CacheEntry(AiInsightResponse response, long expiresAt) {
    }

    private static final String SYSTEM_PROMPT = """
            You are an assistant that narrates pre-computed IoT sensor statistics for a dashboard widget.
            You are given a plain-text list of stats, one entity per item, separated by "; ", in the form:
            "<entity name> (<aggregate type>): <current value><unit>[, previous period was <previous value><unit>]".
            All numbers are already computed - do not recompute, round differently, or invent numbers that are \
            not present in the input.
            Reply with a compact JSON object of exactly this shape: {"summary": string, "trend": "UP" | "DOWN" | "FLAT"}.
            "summary" must be AT MOST 2 short sentences and about 40 words - it is shown in a small dashboard \
            card, so keep it tight and never pad it out. Write plain English a viewer can read at a glance, \
            referencing entity names and their actual values/units. Do not restate the instruction, do not add \
            caveats or generic advice, and do not explain your reasoning. If a previous-period value is present, \
            mention the direction and rough size of the change. If there is only one entity, focus on it directly; \
            if there are several, describe them together naturally instead of listing them one by one.
            "trend" reflects the overall direction across the given entities: "UP" if values are predominantly \
            rising, "DOWN" if predominantly falling, "FLAT" if there is no previous-period value to compare against \
            or changes are negligible.
            Output only the JSON object, nothing else - no markdown, no code fences, no commentary.
            """;

}
