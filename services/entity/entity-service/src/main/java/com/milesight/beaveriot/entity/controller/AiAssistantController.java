package com.milesight.beaveriot.entity.controller;

import com.milesight.beaveriot.base.response.ResponseBody;
import com.milesight.beaveriot.base.response.ResponseBuilder;
import com.milesight.beaveriot.entity.model.request.AiAssistantChatRequest;
import com.milesight.beaveriot.entity.model.response.AiAssistantChatResponse;
import com.milesight.beaveriot.entity.service.AiAssistantChatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai-assistant")
public class AiAssistantController {

    @Autowired
    private AiAssistantChatService aiAssistantChatService;

    @PostMapping("/chat")
    public ResponseBody<AiAssistantChatResponse> chat(@RequestBody @Valid AiAssistantChatRequest request) {
        return ResponseBuilder.success(aiAssistantChatService.chat(request));
    }

}
