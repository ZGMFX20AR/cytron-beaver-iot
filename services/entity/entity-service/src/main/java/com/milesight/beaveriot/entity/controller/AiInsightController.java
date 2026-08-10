package com.milesight.beaveriot.entity.controller;

import com.milesight.beaveriot.base.response.ResponseBody;
import com.milesight.beaveriot.base.response.ResponseBuilder;
import com.milesight.beaveriot.entity.model.request.AiInsightQuery;
import com.milesight.beaveriot.entity.model.response.AiInsightResponse;
import com.milesight.beaveriot.entity.service.AiInsightService;
import com.milesight.beaveriot.permission.aspect.OperationPermission;
import com.milesight.beaveriot.permission.enums.OperationPermissionCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author leon
 */
@RestController
@RequestMapping("/entity")
public class AiInsightController {

    @Autowired
    private AiInsightService aiInsightService;

    @OperationPermission(codes = {OperationPermissionCode.DASHBOARD_EDIT, OperationPermissionCode.DASHBOARD_VIEW})
    @PostMapping("/ai-insight")
    public ResponseBody<AiInsightResponse> aiInsight(@Valid @RequestBody AiInsightQuery query) {
        return ResponseBuilder.success(aiInsightService.generateInsight(query));
    }

}
