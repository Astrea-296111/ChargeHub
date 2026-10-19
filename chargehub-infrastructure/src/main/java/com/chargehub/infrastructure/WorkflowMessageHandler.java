package com.chargehub.infrastructure;

import java.util.Map;

/** Application boundary; implementations keep external calls outside database claim transactions. */
@FunctionalInterface
public interface WorkflowMessageHandler {
    void handle(String eventName, Map<String, Object> payload);
}
