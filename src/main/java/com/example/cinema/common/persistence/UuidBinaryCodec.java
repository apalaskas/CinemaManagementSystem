package com.example.cinema.common.persistence;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

public final class UuidBinaryCodec {

    public static final int BINARY_LENGTH = 16;

    private UuidBinaryCodec() {
    }

    public static byte[] toBytes(UUID value) {
        if (value == null) {
            return null;
        }
        return ByteBuffer.allocate(BINARY_LENGTH)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    public static UUID fromBytes(byte[] value) {
        if (value == null) {
            return null;
        }
        if (value.length != BINARY_LENGTH) {
            throw new IllegalArgumentException("A binary UUID must contain exactly 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static String toCanonicalString(UUID value) {
        return value == null ? null : value.toString();
    }

    public static UUID fromCanonicalString(String value) {
        Objects.requireNonNull(value, "value");
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("UUID must use the canonical lowercase hyphenated form");
        }
        return parsed;
    }
}
