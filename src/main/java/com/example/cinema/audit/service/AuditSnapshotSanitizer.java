package com.example.cinema.audit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AuditSnapshotSanitizer {

    private final ObjectMapper objectMapper;

    public AuditSnapshotSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String safeJson(Object snapshot) {
        if (snapshot == null) {
            return null;
        }
        JsonNode tree = objectMapper.valueToTree(snapshot);
        sanitize(tree);
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Audit snapshot cannot be serialized safely", exception);
        }
    }

    private void sanitize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> secretFields = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (isSecret(field.getKey())) {
                    secretFields.add(field.getKey());
                } else {
                    sanitize(field.getValue());
                }
            }
            object.remove(secretFields);
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::sanitize);
        }
    }

    private static boolean isSecret(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("idempotencykey");
    }
}
