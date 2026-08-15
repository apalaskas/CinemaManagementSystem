package com.example.cinema.common.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.cinema.common.error.InvalidInputException;

@Component
public class EntityTagParser {

    private static final Pattern VERSION_ETAG = Pattern.compile("\"(0|[1-9][0-9]*)\"");

    public long parseVersion(String ifMatch) {
        if (ifMatch == null) {
            throw invalid();
        }
        Matcher matcher = VERSION_ETAG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw invalid();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    public String format(long version) {
        return "\"" + version + "\"";
    }

    private static InvalidInputException invalid() {
        return new InvalidInputException("INVALID_IF_MATCH",
                "If-Match must contain one quoted non-negative numeric version.");
    }
}
