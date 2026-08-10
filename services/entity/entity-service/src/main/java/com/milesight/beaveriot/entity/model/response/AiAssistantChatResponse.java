package com.milesight.beaveriot.entity.model.response;

import lombok.Data;

import java.util.List;

/**
 * The assistant's answer plus a lightweight trace of which data-lookup tools it invoked,
 * so the UI can optionally show "how it got there" and users can trust the numbers.
 */
@Data
public class AiAssistantChatResponse {

    private String reply;

    private List<String> toolCalls;

}
