package com.milesight.beaveriot.entity.model.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * A chat turn sent to the AI assistant. {@code messages} is the full conversation so far
 * (kept client-side), each with a {@code role} of "user" or "assistant" and text content.
 */
@Data
public class AiAssistantChatRequest {

    @NotEmpty
    private List<ChatMessage> messages;

    @Data
    public static class ChatMessage {
        private String role;
        private String content;
    }

}
