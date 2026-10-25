package com.lightchase.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class WireJsonTest {
    private final WireCodec codec = new WireCodec();
    private static final String VALID = "{\"v\":1,\"type\":\"HEARTBEAT\",\"seq\":1,\"requestId\":null,\"matchId\":null,\"body\":{\"nonce\":7}}";

    @Test public void duplicateEnvelopeAndNestedKeysAreRejected() {
        for (String message : Arrays.asList(
                VALID.replace("\"v\":1", "\"v\":2,\"v\":1"),
                VALID.replace("\"nonce\":7", "\"nonce\":8,\"nonce\":7"))) {
            assertBad(message);
        }
    }

    @Test public void commentsSingleQuotesUnquotedKeysAndTrailingContentAreRejected() {
        for (String message : Arrays.asList(
                "/*comment*/" + VALID, VALID.replace('"', '\''),
                VALID.replace("\"nonce\"", "nonce"), VALID + "{}", VALID + " null",
                VALID.replace("\"nonce\":7", "\"nonce\":NaN"),
                VALID.replace("\"nonce\":7", "\"nonce\":Infinity"),
                VALID.replace("\"nonce\":7", "\"nonce\":01"),
                VALID.substring(0, VALID.length() - 1) + ",}", "null", "[]", "")) {
            assertBad(message);
        }
    }

    @Test public void primitiveEnvelopeFieldsMustHaveTheirExactTypesAndBounds() {
        for (String number : Arrays.asList("0", "-1", "1.5", "9007199254740992", "\"1\"", "null", "1e9999999999")) {
            assertBad(VALID.replace("\"seq\":1", "\"seq\":" + number));
        }
        assertBad(VALID.replace("\"requestId\":null", "\"requestId\":\"\""));
        assertBad(VALID.replace("\"requestId\":null", "\"requestId\":\"" + "a".repeat(65) + "\""));
        assertBad(VALID.replace("\"matchId\":null", "\"matchId\":1"));
        codec.decode(VALID.replace("\"seq\":1", "\"seq\":9007199254740991"));
    }

    @Test public void missingEnvelopeFieldsAndUnknownFieldsFail() {
        assertBad(VALID.replace("\"matchId\":null,", ""));
        assertBad(VALID.replace("\"v\":1", "\"v\":1,\"playerId\":\"forged\""));
        assertBad(VALID.replace("\"body\":{\"nonce\":7}", "\"body\":[]"));
    }

    @Test public void sizeLimitCountsUtf8BytesAndIncludesExactBoundary() {
        String prefix = "{\"v\":1,\"type\":\"ERROR\",\"seq\":1,\"requestId\":null,\"matchId\":null,\"body\":{\"code\":\"BAD_MESSAGE\",\"message\":\"";
        String suffix = "\",\"fatal\":false}}";
        int room = WireCodec.MAX_BYTES - prefix.length() - suffix.length();
        String exact = prefix + "a".repeat(room) + suffix;
        assertEquals(WireCodec.MAX_BYTES, exact.getBytes(StandardCharsets.UTF_8).length);
        codec.decodeServer(exact);
        assertEquals("MESSAGE_TOO_LARGE", assertThrows(IllegalArgumentException.class,
                () -> codec.decode(exact + " ")).getMessage());
        String unicode = prefix + "中".repeat(room / 2) + suffix;
        assertTrue(unicode.length() < WireCodec.MAX_BYTES);
        assertEquals("MESSAGE_TOO_LARGE", assertThrows(IllegalArgumentException.class,
                () -> codec.decode(unicode)).getMessage());
    }

    @Test public void excessiveNestingIsAControlledErrorRatherThanStackOverflow() {
        String nested = VALID.replace("{\"nonce\":7}", "{\"nonce\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}");
        assertBad(nested);
    }

    @Test public void wrongMessageDirectionsAndUnknownTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decodeServer(VALID));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(VALID.replace("HEARTBEAT", "ATTACK")));
    }

    private void assertBad(String text) {
        assertEquals("BAD_MESSAGE", assertThrows(text, IllegalArgumentException.class, () -> codec.decode(text)).getMessage());
    }
}
