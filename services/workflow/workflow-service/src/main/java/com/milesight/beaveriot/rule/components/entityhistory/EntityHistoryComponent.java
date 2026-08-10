package com.milesight.beaveriot.rule.components.entityhistory;

import com.milesight.beaveriot.context.api.EntityServiceProvider;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.entity.enums.AggregateType;
import com.milesight.beaveriot.entity.model.request.EntityAggregateQuery;
import com.milesight.beaveriot.entity.model.response.EntityAggregateResponse;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates each selected entity's history over a lookback window (e.g. the average of the
 * last 24 hours) and exposes the results keyed by entity key, so downstream nodes can
 * reference them as {@code #{properties.<nodeId>['<entityKey>']}}.
 * <p>
 * Complements {@code EntitySelectorComponent}, which reads only the latest instantaneous
 * value: use this node when a summary should describe what happened over a period rather
 * than the reading at one moment.
 */
@Slf4j
@RuleNode(value = "entityHistory", type = RuleNodeType.ACTION, description = "Entity History", testable = false)
@Data
public class EntityHistoryComponent implements ProcessorNode<Exchange> {

    @OutputArguments
    @UriParam(javaType = "java.util.List", prefix = "bean", displayName = "Entity Select Setting")
    @UriParamExtension(uiComponent = "entityMultipleSelect")
    private List<String> entities;

    @UriParam(javaType = "string", prefix = "bean", displayName = "Aggregate Type",
            defaultValue = "AVG", enums = "AVG,MIN,MAX,SUM,COUNT,LAST",
            description = "How each entity's history is condensed over the lookback window.")
    private String aggregateType;

    @UriParam(javaType = "integer", prefix = "bean", displayName = "Lookback Minutes",
            defaultValue = "1440",
            description = "How far back from now to aggregate, in minutes. 1440 = last 24 hours.")
    private Integer lookbackMinutes;

    @Autowired
    private EntityServiceProvider entityServiceProvider;

    @Autowired
    private EntityValueService entityValueService;

    @Autowired
    private WorkflowEntityHelper workflowEntityHelper;

    @Override
    public void processor(Exchange exchange) {
        List<String> entityKeys = SpELExpressionHelper.resolveExpression(exchange, entities);
        Map<String, Object> result = new HashMap<>();

        if (!CollectionUtils.isEmpty(entityKeys)) {
            workflowEntityHelper.checkEntityExist(entityKeys);

            long end = System.currentTimeMillis();
            long start = end - resolveLookbackMinutes() * 60_000L;
            AggregateType type = resolveAggregateType();

            for (String entityKey : entityKeys) {
                result.put(entityKey, aggregate(entityKey, type, start, end));
            }
        }

        exchange.getIn().setBody(result);
    }

    private Object aggregate(String entityKey, AggregateType type, long start, long end) {
        Entity entity = entityServiceProvider.findByKey(entityKey);
        if (entity == null || entity.getId() == null) {
            log.warn("Entity history: entity '{}' not found, skipping", entityKey);
            return null;
        }

        EntityAggregateQuery query = new EntityAggregateQuery();
        query.setEntityId(entity.getId());
        query.setAggregateType(type);
        query.setStartTimestamp(start);
        query.setEndTimestamp(end);

        try {
            EntityAggregateResponse response = entityValueService.historyAggregate(query);
            return response == null ? null : response.getValue();
        } catch (Exception e) {
            log.warn("Entity history: aggregation failed for '{}'", entityKey, e);
            return null;
        }
    }

    private AggregateType resolveAggregateType() {
        if (aggregateType == null || aggregateType.isBlank()) {
            return AggregateType.AVG;
        }
        try {
            return AggregateType.valueOf(aggregateType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Entity history: unknown aggregate type '{}', falling back to AVG", aggregateType);
            return AggregateType.AVG;
        }
    }

    private int resolveLookbackMinutes() {
        return lookbackMinutes == null || lookbackMinutes <= 0 ? 1440 : lookbackMinutes;
    }

}
