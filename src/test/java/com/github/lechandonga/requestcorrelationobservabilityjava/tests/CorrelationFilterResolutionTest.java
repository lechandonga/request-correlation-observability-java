package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationDecision;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationFilter;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inbound-header resolution: reuse-or-generate decisions and their reasons.
 */
class CorrelationFilterResolutionTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationFilterResolutionTest.class);

    @Test
    void legalPrimaryHeaderIsReused() {
        String candidate = "trace-legal-0001";
        CorrelationDecision decision = CorrelationFilter.resolve(candidate, null);
        log.info("primary candidate='{}' -> id='{}', generated={}, reason='{}'",
                candidate, decision.correlationId(), decision.generated(), decision.reason());
        assertEquals(candidate, decision.correlationId());
        assertFalse(decision.generated());
        assertTrue(decision.reason().contains(CorrelationIds.HEADER_NAME));
    }

    @Test
    void legalValueIsTrimmedBeforeValidation() {
        String candidate = "  trace-legal-0002  ";
        CorrelationDecision decision = CorrelationFilter.resolve(candidate, null);
        log.info("primary candidate='{}' -> id='{}', generated={}, reason='{}'",
                candidate, decision.correlationId(), decision.generated(), decision.reason());
        assertEquals("trace-legal-0002", decision.correlationId());
        assertFalse(decision.generated());
    }

    @Test
    void fallbackAliasHeaderIsReused() {
        String candidate = "trace-alias-0003aa";
        CorrelationDecision decision = CorrelationFilter.resolve(null, candidate);
        log.info("fallback candidate='{}' -> id='{}', generated={}, reason='{}'",
                candidate, decision.correlationId(), decision.generated(), decision.reason());
        assertEquals(candidate, decision.correlationId());
        assertFalse(decision.generated());
        assertTrue(decision.reason().contains(CorrelationIds.ALTERNATE_HEADER_NAME));
    }

    @Test
    void primaryHeaderWinsOverAlias() {
        CorrelationDecision decision = CorrelationFilter.resolve("trace-primary-0004", "trace-alias-0004aa");
        log.info("both headers present -> id='{}', reason='{}'", decision.correlationId(), decision.reason());
        assertEquals("trace-primary-0004", decision.correlationId());
        assertFalse(decision.generated());
    }

    @Test
    void missingHeadersGenerateNewId() {
        CorrelationDecision decision = CorrelationFilter.resolve(null, null);
        log.info("no headers -> id='{}', generated={}, reason='{}'",
                decision.correlationId(), decision.generated(), decision.reason());
        assertTrue(decision.generated());
        assertTrue(CorrelationIds.isValid(decision.correlationId()));

        CorrelationDecision blank = CorrelationFilter.resolve("   ", "");
        log.info("blank headers -> id='{}', generated={}, reason='{}'",
                blank.correlationId(), blank.generated(), blank.reason());
        assertTrue(blank.generated());
    }

    @Test
    void illegalHeaderIsNormalizedToGeneratedId() {
        String[] illegal = {"bad id with spaces", "short", "x".repeat(129), "bad/slash", "bad\ttab"};
        for (String candidate : illegal) {
            CorrelationDecision decision = CorrelationFilter.resolve(candidate, null);
            log.info("illegal primary candidate='{}' -> normalized id='{}', generated={}, reason='{}'",
                    candidate, decision.correlationId(), decision.generated(), decision.reason());
            assertTrue(decision.generated(), "illegal value must be replaced: " + candidate);
            assertNotEquals(candidate, decision.correlationId());
            assertTrue(CorrelationIds.isValid(decision.correlationId()));
        }
    }

    @Test
    void illegalFallbackHeaderIsAlsoNormalized() {
        CorrelationDecision decision = CorrelationFilter.resolve(null, "short");
        log.info("illegal fallback candidate='short' -> id='{}', generated={}, reason='{}'",
                decision.correlationId(), decision.generated(), decision.reason());
        assertTrue(decision.generated());
        assertTrue(CorrelationIds.isValid(decision.correlationId()));
    }
}
