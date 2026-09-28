package com.github.lechandonga.requestcorrelationobservabilityjava.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolution rules for correlation ids:
 * <ul>
 *     <li>the inbound header is read as {@code X-Correlation-Id}
 *         ({@code X-Request-Id} is accepted as a fallback alias);</li>
 *     <li>a value is "legal" when, after trimming, it consists of
 *         {@code A-Z a-z 0-9 . _ -} and is 8..128 characters long;</li>
 *     <li>an absent or illegal value is replaced by a freshly generated id
 *         ({@code req-} + 32 hex chars), which is always legal and unique.</li>
 * </ul>
 */
public final class CorrelationIds {

    /** Primary inbound/outbound header. */
    public static final String HEADER_NAME = "X-Correlation-Id";
    /** Commonly used alias, accepted when the primary header is absent. */
    public static final String ALTERNATE_HEADER_NAME = "X-Request-Id";

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    private static final Pattern LEGAL = Pattern.compile("[A-Za-z0-9._-]{8,128}");

    private CorrelationIds() {
    }

    /**
     * @return true if the candidate (untrimmed) is a legal correlation id
     */
    public static boolean isValid(String candidate) {
        return candidate != null && LEGAL.matcher(candidate).matches();
    }

    /**
     * Generate a fresh, guaranteed-unique correlation id.
     */
    public static String generate() {
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }
}
