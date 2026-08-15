package com.example.cinema.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class IdempotencyHasher {

    private final ObjectMapper objectMapper;

    public IdempotencyHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] hash(String operation, Object requestContent) {
        try {
            JsonNode tree = objectMapper.valueToTree(requestContent);
            String canonical = operation + "\n" + canonicalJson(tree);
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonicalJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            ((ObjectNode) node).properties().forEach(property -> names.add(property.getKey()));
            names.sort(Comparator.naturalOrder());
            StringBuilder result = new StringBuilder("{");
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) result.append(',');
                String name = names.get(index);
                result.append(quote(name)).append(':').append(canonicalJson(node.get(name)));
            }
            return result.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) result.append(',');
                result.append(canonicalJson(node.get(index)));
            }
            return result.append(']').toString();
        }
        return node.toString();
    }

    private String quote(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Request content cannot be canonicalized", exception);
        }
    }
}
