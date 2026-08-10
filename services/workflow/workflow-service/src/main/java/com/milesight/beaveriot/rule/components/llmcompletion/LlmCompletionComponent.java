package com.milesight.beaveriot.rule.components.llmcompletion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.context.util.ExchangeContextHelper;
import com.milesight.beaveriot.eventbus.api.EventResponse;
import com.milesight.beaveriot.rule.annotations.OutputArguments;
import com.milesight.beaveriot.rule.annotations.RuleNode;
import com.milesight.beaveriot.rule.annotations.UriParamExtension;
import com.milesight.beaveriot.rule.api.ProcessorNode;
import com.milesight.beaveriot.rule.constants.ExchangeHeaders;
import com.milesight.beaveriot.rule.constants.RuleNodeType;
import com.milesight.beaveriot.rule.model.OutputVariablesSettings;
import com.milesight.beaveriot.rule.support.JsonHelper;
import com.milesight.beaveriot.rule.support.SpELExpressionHelper;
import lombok.Data;
import org.apache.camel.Exchange;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.UriParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the LLM Integration's "generate_completion" service entity with a
 * (SpEL-resolvable) prompt and exposes the model's response as a "completion"
 * output variable for downstream workflow nodes.
 */
@RuleNode(value = "llmCompletion", type = RuleNodeType.ACTION, description = "LLM Completion", testable = false)
@Data
public class LlmCompletionComponent implements ProcessorNode<Exchange> {

    private static final String GENERATE_COMPLETION_KEY_PREFIX = "llm-integration.integration.generate_completion";

    private static final String MODELS_KEY = "llm-integration.integration.models";

    @UriParam(javaType = "string", prefix = "bean", displayName = "Prompt")
    // promptEditor = plain-text editor with an upstream-variable picker, so users can insert
    // #{properties.<nodeId>['<key>']} references from a dropdown instead of typing them.
    @UriParamExtension(uiComponent = "promptEditor", loggable = true)
    private String prompt;

    @UriParam(javaType = "string", prefix = "bean", displayName = "Model",
            description = "Leave blank to use the first model configured on the LLM Integration settings page.")
    @UriParamExtension(uiComponent = "llmModelSelect")
    private String model;

    @Autowired
    EntityValueServiceProvider entityValueServiceProvider;

    /**
     * This node always emits a single fixed key, {@code completion}, so the output list is
     * not user-editable: {@code autowired = true} hides it from the config form (the same
     * approach {@code HttpRequestComponent} uses for its fixed outputs). Renaming it in the
     * UI would otherwise produce a variable reference that never resolves. The frontend
     * declares the matching static output so the variable picker still offers it.
     */
    @UriParamExtension(uiComponent = "paramDefineInput", initialValue = "[{\"name\":\"completion\",\"type\":\"STRING\"}]")
    @OutputArguments(displayName = "Output Variables")
    @UriParam(prefix = "bean", name = "output", displayName = "Output Variables", description = "The model's completion text.")
    @Metadata(autowired = true)
    private List<OutputVariablesSettings> output;

    public void setOutput(String json) {
        if (StringUtils.hasText(json)) {
            output = JsonHelper.fromJSON(json, new TypeReference<List<OutputVariablesSettings>>() {
            });
        }
    }

    @Override
    public void processor(Exchange exchange) {
        Object resolvedPrompt = SpELExpressionHelper.resolveStringExpression(exchange, prompt);

        Map<String, Object> serviceParams = new HashMap<>();
        serviceParams.put(GENERATE_COMPLETION_KEY_PREFIX + ".prompt", resolvedPrompt == null ? null : resolvedPrompt.toString());
        serviceParams.put(GENERATE_COMPLETION_KEY_PREFIX + ".model", StringUtils.hasText(model) ? model : resolveDefaultModel());

        ExchangePayload exchangePayload = ExchangePayload.create(serviceParams);
        if (ObjectUtils.isEmpty(exchange.getProperty(ExchangeHeaders.EXCHANGE_ROOT_FLOW_ID))) {
            exchange.setProperty(ExchangeHeaders.EXCHANGE_ROOT_FLOW_ID, exchange.getFromRouteId());
        }
        ExchangeContextHelper.initializeExchangeContext(exchangePayload, exchange);

        EventResponse eventResponse = entityValueServiceProvider.saveValuesAndPublishSync(exchangePayload);

        Map<String, Object> result = new HashMap<>();
        result.put("completion", eventResponse.get("response"));
        OutputVariablesSettings.validate(result, output);

        exchange.getIn().setBody(result);
    }

    private String resolveDefaultModel() {
        Object modelsValue = entityValueServiceProvider.findValueByKey(MODELS_KEY);
        if (modelsValue == null || !StringUtils.hasText(modelsValue.toString())) {
            return null;
        }
        String[] models = modelsValue.toString().split(",");
        return models.length > 0 ? models[0].trim() : null;
    }

}
