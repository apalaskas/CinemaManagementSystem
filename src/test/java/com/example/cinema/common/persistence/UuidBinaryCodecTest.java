package com.example.cinema.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidBinaryCodecTest {

    @Test
    void roundTripsCanonicalUuidThroughSixteenBytes() {
        UUID value = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        byte[] binary = UuidBinaryCodec.toBytes(value);

        assertThat(binary).hasSize(16);
        assertThat(UuidBinaryCodec.fromBytes(binary)).isEqualTo(value);
        assertThat(UuidBinaryCodec.toCanonicalString(value)).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(UuidBinaryCodec.fromCanonicalString("123e4567-e89b-12d3-a456-426614174000"))
                .isEqualTo(value);
    }

    @Test
    void rejectsNonSixteenByteValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UuidBinaryCodec.fromBytes(new byte[15]))
                .withMessageContaining("16 bytes");
    }

    @Test
    void preservesNullForNullableInfrastructureValues() {
        assertThat(UuidBinaryCodec.toBytes(null)).isNull();
        assertThat(UuidBinaryCodec.fromBytes(null)).isNull();
        assertThat(UuidBinaryCodec.toCanonicalString(null)).isNull();
    }

    @Test
    void rejectsNonCanonicalUuidText() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UuidBinaryCodec.fromCanonicalString("123E4567-E89B-12D3-A456-426614174000"))
                .withMessageContaining("canonical");
    }
}
