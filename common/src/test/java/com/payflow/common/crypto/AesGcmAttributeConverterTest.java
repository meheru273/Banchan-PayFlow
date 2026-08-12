package com.payflow.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmAttributeConverterTest {

    private final AesGcmAttributeConverter converter = new AesGcmAttributeConverter("unit-test-secret");

    @Test
    void roundTripsPlaintext() {
        String encrypted = converter.convertToDatabaseColumn("tok_visa_4242");
        assertThat(encrypted).isNotEqualTo("tok_visa_4242");
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo("tok_visa_4242");
    }

    @Test
    void usesAFreshIvPerValue() {
        String first = converter.convertToDatabaseColumn("same-input");
        String second = converter.convertToDatabaseColumn("same-input");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tamperedCiphertextDecryptsToNull() {
        byte[] raw = Base64.getDecoder().decode(converter.convertToDatabaseColumn("tok_visa_4242"));
        raw[raw.length - 1] ^= 0x01;
        assertThat(converter.convertToEntityAttribute(Base64.getEncoder().encodeToString(raw))).isNull();
    }

    @Test
    void wrongKeyDecryptsToNull() {
        String encrypted = converter.convertToDatabaseColumn("tok_visa_4242");
        AesGcmAttributeConverter otherKey = new AesGcmAttributeConverter("a-different-secret");
        assertThat(otherKey.convertToEntityAttribute(encrypted)).isNull();
    }

    @Test
    void legacyPlaintextReadsAsNullInsteadOfFailing() {
        assertThat(converter.convertToEntityAttribute("tok_visa_4242")).isNull();
    }

    @Test
    void nullPassesThrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void acceptsBase64EncodedKeyToo() {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        AesGcmAttributeConverter withRawKey = new AesGcmAttributeConverter(base64Key);
        String encrypted = withRawKey.convertToDatabaseColumn("secret-value");
        assertThat(withRawKey.convertToEntityAttribute(encrypted)).isEqualTo("secret-value");
    }
}
