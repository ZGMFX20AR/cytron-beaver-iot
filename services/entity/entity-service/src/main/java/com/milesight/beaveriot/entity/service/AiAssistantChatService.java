package com.milesight.beaveriot.entity.service;

import com.milesight.beaveriot.base.exception.ServiceException;
import com.milesight.beaveriot.base.utils.JsonUtils;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.integration.model.AttributeBuilder;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.entity.dto.EntityQuery;
import com.milesight.beaveriot.entity.dto.EntityResponse;
import com.milesight.beaveriot.entity.enums.AggregateType;
import com.milesight.beaveriot.entity.model.request.AiAssistantChatRequest;
import com.milesight.beaveriot.entity.model.request.EntityAggregateQuery;
import com.milesight.beaveriot.entity.model.response.AiAssistantChatResponse;
import com.milesight.beaveriot.entity.model.response.EntityAggregateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backs the AI Assistant chat: an OpenAI-compatible tool-calling loop that lets the model
 * answer analysis questions over live entity data. Detection of "what to fetch" is delegated
 * to the model, but every number in the answer comes from a real tool call against this
 * server's own query methods - the model never sees or invents raw values on its own.
 * <p>
 * Requires the LLM Integration to be set to a provider exposing {@code /v1/chat/completions}:
 * OpenAI or OpenRouter (both need an API key), or Ollama (unauthenticated). Two modes, chosen by
 * a one-time probe: endpoints with native tool calling run the free tool loop below; Ollama
 * endpoints that ignore the {@code tools} parameter (hailo-ollama) fall back to the guided
 * pipeline, where this code does the retrieval and the model only phrases the answer. In both
 * modes every number shown to the user comes from a real query, never from the model.
 */
@Slf4j
@Service
public class AiAssistantChatService {

    private static final String KEY_PREFIX = "llm-integration.integration.";
    private static final String KEY_PROVIDER = KEY_PREFIX + "llm_properties.provider_type";
    private static final String KEY_BASE_URL = KEY_PREFIX + "llm_properties.base_url";
    private static final String KEY_API_KEY = KEY_PREFIX + "llm_properties.api_key";
    private static final String KEY_MODELS = KEY_PREFIX + "models";

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int SEARCH_LIMIT = 50;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Autowired
    private EntityService entityService;

    @Autowired
    private EntityValueService entityValueService;

    @Autowired
    private EntityValueServiceProvider entityValueServiceProvider;

    @Value("${beaver-iot.ai-assistant.model:}")
    private String configuredModel;

    /**
     * Whether a given Ollama endpoint+model honours the {@code tools} parameter, keyed by
     * "baseUrl|model". Probed once per JVM: the answer is a property of the server build and the
     * model, so re-checking on every message would just burn a generation each time.
     */
    private final Map<String, Boolean> ollamaToolSupport = new ConcurrentHashMap<>();

    public AiAssistantChatResponse chat(AiAssistantChatRequest request) {
        LlmConfig config = resolveConfig();
        // An Ollama endpoint that ignores the tools parameter (hailo-ollama does) cannot drive
        // the tool-calling loop below - but it can still answer through the guided pipeline,
        // where our code does the entity selection and fetching and the model only extracts
        // the question's intent and phrases the answer. Same division of labour as AI Insight.
        if (config.ollama() && !supportsToolCalling(config)) {
            return guidedChat(config, request);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        for (AiAssistantChatRequest.ChatMessage m : request.getMessages()) {
            String role = "assistant".equalsIgnoreCase(m.getRole()) ? "assistant" : "user";
            messages.add(Map.of("role", role, "content", m.getContent() == null ? "" : m.getContent()));
        }

        List<String> toolTrace = new ArrayList<>();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> assistantMessage = callChatApi(config, messages);
            messages.add(assistantMessage);

            List<Map<String, Object>> toolCalls = asList(assistantMessage.get("tool_calls"));
            if (toolCalls.isEmpty()) {
                AiAssistantChatResponse response = new AiAssistantChatResponse();
                response.setReply(String.valueOf(assistantMessage.getOrDefault("content", "")));
                response.setToolCalls(toolTrace);
                return response;
            }

            for (Map<String, Object> toolCall : toolCalls) {
                Map<String, Object> function = asMap(toolCall.get("function"));
                String name = String.valueOf(function.get("name"));
                String argsJson = String.valueOf(function.getOrDefault("arguments", "{}"));
                toolTrace.add(name + "(" + argsJson + ")");

                String result = executeTool(name, argsJson);

                Map<String, Object> toolMessage = new java.util.LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCall.get("id"));
                toolMessage.put("content", result);
                messages.add(toolMessage);
            }
        }

        AiAssistantChatResponse response = new AiAssistantChatResponse();
        response.setReply("I wasn't able to finish analysing that within the allowed number of steps. "
                + "Try narrowing the question to a specific entity or time range.");
        response.setToolCalls(toolTrace);
        return response;
    }

    // ---------------------------------------------------------------------------------------
    // Guided mode (Ollama endpoints without native tool calling, e.g. hailo-ollama)
    // ---------------------------------------------------------------------------------------

    /**
     * Answers without native tool calling by inverting who decides what to fetch: a rule-based
     * parser distils the question into measurement keywords, an intent and a lookback (step 1),
     * this code performs the entity search and value/aggregate reads deterministically (step 2),
     * and the model's only job is phrasing an answer from the fetched numbers (step 3). Same
     * division of labour that lets AI Insight run on the NPU model - the model never chooses
     * tools and never supplies numbers of its own.
     *
     * <p>Step 1 is code, not a model call, on measured evidence: qwen2.5:1.5b on hailo-ollama
     * cannot hold even a flat four-field JSON schema (it invents fields and hallucinates dates,
     * with or without few-shot examples). Regexes are less flexible but never drift.
     *
     * <p>The trade-off against the native loop: one retrieval per question, so multi-hop requests
     * ("compare X and Y across all devices") come back simpler.
     *
     * <p>All message content sent from here must be single-line: hailo-ollama's prompt templater
     * fails on raw newlines in content ("control character U+000A must be escaped", oatpp 1.4.0).
     */
    private AiAssistantChatResponse guidedChat(LlmConfig config, AiAssistantChatRequest request) {
        String question = lastUserQuestion(request);
        List<String> toolTrace = new ArrayList<>();

        // ---- Step 1: rule-based intent extraction ----
        String intentType = detectIntent(question);
        int lookbackMinutes = parseLookbackMinutes(question);
        List<String> keywords = contentWords(question);
        if (keywords.isEmpty()) {
            AiAssistantChatResponse response = new AiAssistantChatResponse();
            response.setReply("I couldn't work out which measurement that refers to. Try naming it directly, "
                    + "e.g. \"What is the current temperature?\" or \"Average humidity over the last 24 hours\".");
            response.setToolCalls(toolTrace);
            return response;
        }

        // ---- Step 2: deterministic retrieval through the same tools as the native loop ----
        // Try each content word as a search keyword until one matches ("max power on the Office
        // sensor" -> "power" hits even though "office" alone might not).
        List<Map<String, Object>> candidates = new ArrayList<>();
        String matchedKeyword = "";
        for (String keyword : keywords) {
            String searchArgs = JsonUtils.toJSON(Map.of("keyword", keyword));
            toolTrace.add("search_entities(" + searchArgs + ")");
            Map<String, Object> searchResult = JsonUtils.toMap(executeTool("search_entities", searchArgs));
            for (Object o : asList(searchResult.get("entities"))) {
                candidates.add(asMap(o));
            }
            if (!candidates.isEmpty()) {
                matchedKeyword = keyword;
                break;
            }
        }
        List<Map<String, Object>> selected = selectEntities(candidates, matchedKeyword, question);
        if (selected.isEmpty()) {
            AiAssistantChatResponse response = new AiAssistantChatResponse();
            response.setReply("I couldn't find any entity matching \"" + String.join("\", \"", keywords)
                    + "\". Check the entity name on the Entity page and try that wording.");
            response.setToolCalls(toolTrace);
            return response;
        }

        String dataJson;
        if ("current".equals(intentType)) {
            List<String> keys = selected.stream().map(e -> String.valueOf(e.get("key"))).toList();
            String args = JsonUtils.toJSON(Map.of("entity_keys", keys));
            toolTrace.add("get_latest_values(" + args + ")");
            Map<String, Object> values = JsonUtils.toMap(executeTool("get_latest_values", args));
            dataJson = JsonUtils.toJSON(Map.of("entities", selected, "latest", values));
        } else {
            List<Map<String, Object>> aggregates = new ArrayList<>();
            for (Map<String, Object> entity : selected) {
                String args = JsonUtils.toJSON(Map.of(
                        "entity_key", String.valueOf(entity.get("key")),
                        "aggregate_type", intentType.toUpperCase(),
                        "lookback_minutes", lookbackMinutes));
                toolTrace.add("get_history_aggregate(" + args + ")");
                aggregates.add(JsonUtils.toMap(executeTool("get_history_aggregate", args)));
            }
            dataJson = JsonUtils.toJSON(Map.of("entities", selected, "aggregates", aggregates));
        }

        // ---- Step 3: phrase the answer from the fetched data only ----
        Map<String, Object> answer = callChatApi(config, List.of(
                Map.of("role", "system", "content", GUIDED_ANSWER_PROMPT),
                Map.of("role", "user",
                        "content", "Question: " + singleLine(question) + " Data: " + dataJson)), false);

        AiAssistantChatResponse response = new AiAssistantChatResponse();
        response.setReply(String.valueOf(answer.getOrDefault("content", "")));
        response.setToolCalls(toolTrace);
        return response;
    }

    /**
     * Ranks search hits for the guided path: entities whose device name appears in the question
     * first (matching against the known device names is far more reliable than extracting a
     * device from free text), then exact name-matches of the measurement over partial ones,
     * mirroring the "the live reading is the entity named just after the measurement, not a
     * settings entity" rule the native loop's prompt teaches the model. Capped so one broad
     * keyword cannot fan out into dozens of reads.
     */
    private static List<Map<String, Object>> selectEntities(
            List<Map<String, Object>> candidates, String keyword, String question) {
        String questionLower = question.toLowerCase();
        List<Map<String, Object>> pool = candidates;
        List<Map<String, Object>> deviceMatches = candidates.stream()
                .filter(e -> {
                    String device = String.valueOf(e.getOrDefault("device", "")).toLowerCase().trim();
                    return !device.isEmpty() && !"null".equals(device) && questionLower.contains(device);
                })
                .toList();
        if (!deviceMatches.isEmpty()) {
            pool = deviceMatches;
        }
        String keywordLower = keyword.toLowerCase();
        List<Map<String, Object>> ranked = new ArrayList<>(pool);
        ranked.sort(java.util.Comparator.comparingInt(e -> {
            String name = String.valueOf(e.getOrDefault("name", "")).toLowerCase();
            if (name.equals(keywordLower)) {
                return 0;
            }
            return name.contains(keywordLower) ? 1 : 2;
        }));
        return ranked.subList(0, Math.min(ranked.size(), GUIDED_MAX_ENTITIES));
    }

    private static String lastUserQuestion(AiAssistantChatRequest request) {
        List<AiAssistantChatRequest.ChatMessage> messages = request.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiAssistantChatRequest.ChatMessage m = messages.get(i);
            if (!"assistant".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                return m.getContent();
            }
        }
        return "";
    }

    /** Maps question phrasing to an aggregate intent; "current" when no period wording is found. */
    private static String detectIntent(String question) {
        String q = question.toLowerCase();
        if (q.matches(".*\\b(average|avg|mean)\\b.*")) {
            return "avg";
        }
        if (q.matches(".*\\b(max|maximum|peak|highest)\\b.*")) {
            return "max";
        }
        if (q.matches(".*\\b(min|minimum|lowest)\\b.*")) {
            return "min";
        }
        if (q.matches(".*\\b(sum|total)\\b.*")) {
            return "sum";
        }
        if (q.contains("how many") || q.contains("number of") || q.matches(".*\\bcount\\b.*")) {
            return "count";
        }
        return "current";
    }

    private static final Pattern LOOKBACK_PATTERN =
            Pattern.compile("(?:last|past)\\s*(\\d+)?\\s*(minute|min|hour|hr|day|week|month)", Pattern.CASE_INSENSITIVE);

    /** "last 24 hours" -> 1440, "past week" -> 10080, "today" -> 1440; 24h when nothing is stated. */
    private static int parseLookbackMinutes(String question) {
        String q = question.toLowerCase();
        Matcher m = LOOKBACK_PATTERN.matcher(q);
        if (m.find()) {
            int n = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            int unitMinutes = switch (m.group(2)) {
                case "minute", "min" -> 1;
                case "hour", "hr" -> 60;
                case "day" -> 1440;
                case "week" -> 10080;
                default -> 43200; // month
            };
            return n * unitMinutes;
        }
        if (q.contains("today")) {
            return 1440;
        }
        if (q.contains("this week")) {
            return 10080;
        }
        return 1440;
    }

    /**
     * The question's candidate measurement words: lowercased, split on non-alphanumerics, with
     * question scaffolding ("what", "average", "sensor", ...) removed so what remains is likely
     * to be entity vocabulary ("temperature", "co2", "power").
     */
    private static List<String> contentWords(String question) {
        List<String> words = new ArrayList<>();
        for (String word : question.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() >= 3 && !GUIDED_STOPWORDS.contains(word) && !words.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static final int GUIDED_MAX_ENTITIES = 3;

    private static final Set<String> GUIDED_STOPWORDS = Set.of(
            "what", "whats", "was", "were", "the", "and", "for", "over", "during", "with", "from",
            "how", "many", "much", "number", "show", "tell", "give", "get", "please", "can", "you",
            "value", "values", "reading", "readings", "level", "levels", "data", "sensor", "sensors",
            "device", "devices", "entity", "entities", "right", "now", "current", "currently",
            "latest", "today", "yesterday", "this", "that", "last", "past", "recent", "recently",
            "minute", "minutes", "hour", "hours", "day", "days", "week", "weeks", "month", "months",
            "average", "avg", "mean", "maximum", "max", "peak", "highest", "minimum", "min",
            "lowest", "sum", "total", "count", "all", "any", "are", "there", "have", "has", "been");

    private static final String GUIDED_ANSWER_PROMPT = "You are the AI Assistant for a Beaver IoT"
            + " dashboard. Answer the user's question using ONLY the values in the provided data."
            + " Never invent or adjust numbers - every number in your answer must appear in the"
            + " data. Name the entity and its device, and include units when present. If a value"
            + " is null or missing, say that data is unavailable for it - do not guess. Answer in"
            + " one or two plain sentences. No JSON, no code.";

    // ---------------------------------------------------------------------------------------
    // Tool-calling capability
    // ---------------------------------------------------------------------------------------

    /**
     * Whether this Ollama endpoint honours the {@code tools} parameter, deciding which of the two
     * assistant modes runs: the native tool-calling loop, or the guided pipeline.
     *
     * <p>The distinction matters because a server that drops the parameter degrades silently: it
     * returns ordinary prose, the loop treats it as a final answer, and the user gets a fluent
     * reply with no entity data behind it. Measured on hailo-ollama (oatpp 1.4.0): the
     * {@code tools} array is accepted and discarded, with no {@code tool_calls} in the response.
     *
     * <p>Only Ollama is checked - OpenAI and OpenRouter both implement tool calling, and probing
     * them would spend a paid request to learn nothing.
     */
    private boolean supportsToolCalling(LlmConfig config) {
        String cacheKey = config.baseUrl() + "|" + config.model();
        return ollamaToolSupport.computeIfAbsent(cacheKey, key -> {
            boolean result = probeToolCalling(config);
            log.info("Tool-calling support for model '{}' at {}: {}", config.model(), config.baseUrl(), result);
            return result;
        });
    }

    /**
     * Asks the model to do nothing but call a tool. A tool-capable endpoint answers with
     * {@code tool_calls}; one that drops the parameter answers with prose.
     */
    private boolean probeToolCalling(LlmConfig config) {
        try {
            Map<String, Object> reply = callChatApi(config, List.of(
                    Map.of("role", "system", "content",
                            "You must respond by calling a tool. Never reply with prose."),
                    Map.of("role", "user", "content",
                            "Call search_entities with the keyword \"temperature\".")));
            return !asList(reply.get("tool_calls")).isEmpty();
        } catch (Exception e) {
            // An unreachable or erroring endpoint is a different fault. Assume capable so the real
            // request surfaces the actual error instead of a misleading "no tool support" message.
            log.warn("Could not probe tool-calling support at {}: {}", config.baseUrl(), e.getMessage());
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tools
    // ---------------------------------------------------------------------------------------

    private String executeTool(String name, String argsJson) {
        try {
            Map<String, Object> args = JsonUtils.toMap(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return switch (name) {
                case "search_entities" -> toolSearchEntities(args);
                case "get_latest_values" -> toolGetLatestValues(args);
                case "get_history_aggregate" -> toolGetHistoryAggregate(args);
                default -> "{\"error\":\"unknown tool: " + name + "\"}";
            };
        } catch (Exception e) {
            log.warn("AI assistant: tool '{}' failed", name, e);
            return "{\"error\":\"" + safe(e.getMessage()) + "\"}";
        }
    }

    private String toolSearchEntities(Map<String, Object> args) {
        String keyword = String.valueOf(args.getOrDefault("keyword", "")).trim();
        EntityQuery query = new EntityQuery();
        query.setKeyword(keyword);
        query.setPageNumber(1);
        query.setPageSize(SEARCH_LIMIT);
        // search() dereferences the sort without a null-check, so it must be set explicitly.
        query.sort(new com.milesight.beaveriot.base.page.Sorts().desc("id"));

        Page<EntityResponse> page = entityService.search(query);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityResponse e : page.getContent()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("key", e.getEntityKey());
            item.put("name", e.getEntityName());
            item.put("device", e.getDeviceName());
            item.put("valueType", e.getEntityValueType());
            Object unit = e.getEntityValueAttribute() == null ? null : e.getEntityValueAttribute().get("unit");
            if (unit != null) {
                item.put("unit", unit);
            }
            out.add(item);
        }
        return JsonUtils.toJSON(Map.of("entities", out, "count", out.size()));
    }

    private String toolGetLatestValues(Map<String, Object> args) {
        List<String> keys = asStringList(args.get("entity_keys"));
        if (keys.isEmpty()) {
            return "{\"error\":\"entity_keys is required\"}";
        }
        Map<String, Object> values = entityValueServiceProvider.findValuesByKeys(keys);
        return JsonUtils.toJSON(Map.of("values", values));
    }

    private String toolGetHistoryAggregate(Map<String, Object> args) {
        String entityKey = String.valueOf(args.getOrDefault("entity_key", "")).trim();
        if (entityKey.isEmpty()) {
            return "{\"error\":\"entity_key is required\"}";
        }
        Entity entity = entityService.findByKey(entityKey);
        if (entity == null || entity.getId() == null) {
            return "{\"error\":\"entity not found: " + safe(entityKey) + "\"}";
        }

        AggregateType aggregateType = parseAggregate(String.valueOf(args.getOrDefault("aggregate_type", "AVG")));
        int lookbackMinutes = toInt(args.get("lookback_minutes"), 1440);
        long end = System.currentTimeMillis();
        long start = end - (long) lookbackMinutes * 60_000L;

        EntityAggregateQuery aggregateQuery = new EntityAggregateQuery();
        aggregateQuery.setEntityId(entity.getId());
        aggregateQuery.setAggregateType(aggregateType);
        aggregateQuery.setStartTimestamp(start);
        aggregateQuery.setEndTimestamp(end);

        EntityAggregateResponse aggregate = entityValueService.historyAggregate(aggregateQuery);
        Object value = aggregate == null ? null : aggregate.getValue();
        String unit = entity.getAttributeStringValue(AttributeBuilder.ATTRIBUTE_UNIT);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("entity_key", entityKey);
        result.put("name", entity.getName());
        result.put("aggregate_type", aggregateType.name());
        result.put("lookback_minutes", lookbackMinutes);
        result.put("value", value);
        if (unit != null) {
            result.put("unit", unit);
        }
        return JsonUtils.toJSON(result);
    }

    private static List<Map<String, Object>> toolDefinitions() {
        return List.of(
                functionTool("search_entities",
                        "Find IoT entities (sensors/measurements) by keyword. Returns their exact keys, names, units and value types. Always call this first to discover the correct entity_key before reading values.",
                        Map.of("type", "object",
                                "properties", Map.of("keyword", Map.of("type", "string",
                                        "description", "Search term, e.g. 'temperature', 'humidity', a device name")),
                                "required", List.of("keyword"))),
                functionTool("get_latest_values",
                        "Get the current (latest) value of one or more entities by their exact keys.",
                        Map.of("type", "object",
                                "properties", Map.of("entity_keys", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Exact entity keys from search_entities")),
                                "required", List.of("entity_keys"))),
                functionTool("get_history_aggregate",
                        "Aggregate one entity's history over a lookback window - use for questions about averages, peaks, minimums or totals over a period.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "entity_key", Map.of("type", "string", "description", "Exact entity key"),
                                        "aggregate_type", Map.of("type", "string",
                                                "enum", List.of("AVG", "MIN", "MAX", "SUM", "COUNT", "LAST"),
                                                "description", "How to aggregate"),
                                        "lookback_minutes", Map.of("type", "integer",
                                                "description", "How far back from now, in minutes. 1440 = last 24 hours, 10080 = last 7 days")),
                                "required", List.of("entity_key", "aggregate_type", "lookback_minutes")))
        );
    }

    private static Map<String, Object> functionTool(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function",
                Map.of("name", name, "description", description, "parameters", parameters));
    }

    // ---------------------------------------------------------------------------------------
    // LLM call
    // ---------------------------------------------------------------------------------------

    private Map<String, Object> callChatApi(LlmConfig config, List<Map<String, Object>> messages) {
        return callChatApi(config, messages, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callChatApi(LlmConfig config, List<Map<String, Object>> messages, boolean includeTools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages);
        if (includeTools) {
            body.put("tools", toolDefinitions());
        }
        body.put("temperature", 0.2);
        body.put("max_tokens", 1024);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJSON(body)));

        // Ollama has no authentication; sending an empty bearer token makes some builds
        // reject the request outright, so only set the header when a key is configured.
        if (StringUtils.hasText(config.apiKey())) {
            requestBuilder.header("Authorization", "Bearer " + config.apiKey());
        }

        HttpRequest httpRequest = requestBuilder.build();

        try {
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = JsonUtils.toMap(httpResponse.body());
            if (httpResponse.statusCode() >= 300 || parsed.containsKey("error")) {
                String detail = parsed.get("error") instanceof Map<?, ?> errMap
                        ? String.valueOf(errMap.get("message"))
                        : httpResponse.body();
                throw ServiceException.with(com.milesight.beaveriot.base.enums.ErrorCode.SERVER_ERROR)
                        .detailMessage("LLM provider error: " + detail).build();
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw ServiceException.with(com.milesight.beaveriot.base.enums.ErrorCode.SERVER_ERROR)
                        .detailMessage("LLM provider returned no choices").build();
            }
            return (Map<String, Object>) choices.get(0).get("message");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw ServiceException.with(com.milesight.beaveriot.base.enums.ErrorCode.SERVER_ERROR)
                    .detailMessage("Failed to reach LLM provider: " + e.getMessage()).build();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------------------------

    private LlmConfig resolveConfig() {
        String provider = readString(KEY_PROVIDER, "ollama").toLowerCase();
        String apiKey = readString(KEY_API_KEY, "");
        String baseUrl = readString(KEY_BASE_URL, "");

        // Ollama serves the same /v1/chat/completions surface as OpenAI, so it can drive this
        // tool-calling loop too. Whether a given local model actually emits tool_calls depends
        // on the model; if it does not, the loop simply returns its first plain reply.
        boolean isOllama = "ollama".equals(provider);

        if (!"openai".equals(provider) && !"openrouter".equals(provider) && !isOllama) {
            throw ServiceException.with(com.milesight.beaveriot.base.enums.ErrorCode.PARAMETER_VALIDATION_FAILED)
                    .detailMessage("The AI Assistant needs an OpenAI-compatible provider (OpenAI, OpenRouter or Ollama). "
                            + "Set one on the LLM Integration page.")
                    .build();
        }
        // Ollama is unauthenticated; the hosted providers are not
        if (!isOllama && !StringUtils.hasText(apiKey)) {
            throw ServiceException.with(com.milesight.beaveriot.base.enums.ErrorCode.PARAMETER_VALIDATION_FAILED)
                    .detailMessage("No API key is configured for the LLM Integration. Add one on the LLM Integration page.")
                    .build();
        }
        if (!StringUtils.hasText(baseUrl)) {
            if (isOllama) {
                throw ServiceException.with(com.milesight.beaveriot.base.enums.ErrorCode.PARAMETER_VALIDATION_FAILED)
                        .detailMessage("No Base URL is set for Ollama. Open the LLM Integration page and save the "
                                + "settings once to auto-detect the server, or enter it manually.")
                        .build();
            }
            baseUrl = "openrouter".equals(provider) ? "https://openrouter.ai/api" : "https://api.openai.com";
        }
        // Tolerate a scheme-less value typed into the settings page, e.g. "localhost:11434"
        if (!baseUrl.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            baseUrl = "http://" + baseUrl;
        }
        baseUrl = baseUrl.replaceAll("/+$", "");

        return new LlmConfig(baseUrl, apiKey, resolveModel(provider), isOllama);
    }

    private String resolveModel(String provider) {
        if (StringUtils.hasText(configuredModel)) {
            return configuredModel;
        }
        String models = readString(KEY_MODELS, "");
        if (StringUtils.hasText(models)) {
            String first = models.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }
        if ("ollama".equals(provider)) {
            // A hosted-model default would just 404 against a local server, and the resulting
            // error would point at the wrong thing
            throw ServiceException.with(com.milesight.beaveriot.base.enums.ErrorCode.PARAMETER_VALIDATION_FAILED)
                    .detailMessage("No Ollama model is available. Open the LLM Integration page and run "
                            + "Test connection to discover the installed models.")
                    .build();
        }
        return "openrouter".equals(provider) ? "openai/gpt-4o" : "gpt-4o";
    }

    private String readString(String key, String defaultValue) {
        Object value = entityValueServiceProvider.findValueByKey(key);
        return value == null || !StringUtils.hasText(value.toString()) ? defaultValue : value.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static AggregateType parseAggregate(String value) {
        try {
            return AggregateType.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            return AggregateType.AVG;
        }
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? defaultValue : (int) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ");
    }

    private record LlmConfig(String baseUrl, String apiKey, String model, boolean ollama) {
    }

    private static final String SYSTEM_PROMPT = """
            You are the AI Assistant for a Beaver IoT dashboard. You help users analyse their sensor
            and device data by answering questions in plain English.
            You have tools to look up real data. Rules:
            - NEVER invent or guess entity keys, values, units, or timestamps. Only state numbers that
              a tool actually returned in this conversation.
            - To find an entity, call search_entities first to get its exact key, then read it.
            - Search by the MEASUREMENT type (e.g. "temperature", "humidity"), not by the device name.
              Each result includes a "device" field - when the user names a specific device, pick the
              matching entity by its "device" field. A device usually also has many *settings* entities
              (calibration, alarm thresholds, etc.); the actual live reading is the one whose name is
              just the measurement (e.g. name "Temperature"), not a settings/config entity.
            - If the first search is too broad or misses the reading, search again with a better keyword
              before giving up.
            - Use get_latest_values for "now/current" questions and get_history_aggregate for
              averages, peaks, minimums or totals over a period.
            - If a reading or aggregate comes back null/empty, that entity may be inactive: when several
              entities matched, try the other matching ones before concluding data is unavailable. When
              you do report a value from an ambiguous match, name which device it came from.
            - If a tool genuinely returns an error or no data after reasonable retries, say so plainly
              rather than making something up.
            - Keep answers concise and reference the actual entity names, values and units.
            - You are read-only: you cannot change settings or control devices. If asked to, explain that.
            """;

}
