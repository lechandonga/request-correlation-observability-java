package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation and generation rules for correlation ids. Every assertion logs
 * the candidate and the decision basis.
 */
class CorrelationIdsTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdsTest.class);

    @Test
    void legalValuesAreAccepted() {
        String[] candidates = {
                "abcdefgh",                       // 8 chars, lower bound
                "trace-1234.5678_abcd",
                "A1B2C3D4E5F6G7H8",
                "x".repeat(128),                  // 128 chars, upper bound
                "req-0123456789abcdef0123456789abcdef"
        };
        for (String candidate : candidates) {
            boolean valid = CorrelationIds.isValid(candidate);
            log.info("candidate='{}' -> valid={} (basis: {}..{} chars of [A-Za-z0-9._-])",
                    candidate, valid, CorrelationIds.MIN_LENGTH, CorrelationIds.MAX_LENGTH);
            assertTrue(valid, "expected legal: " + candidate);
        }
    }

    @Test
    void illegalValuesAreRejected() {
        String[] candidates = {
                null,
                "",
                "       ",
                "short",                          // 6 chars, too short
                "x".repeat(129),                  // 129 chars, too long
                "has space in it",
                "has/slash",
                "has\ttab",
                "hás-unicode-é",                  // non-ASCII
                "quote\"injection"
        };
        for (String candidate : candidates) {
            boolean valid = CorrelationIds.isValid(candidate);
            log.info("candidate='{}' -> valid={} (basis: must be {}..{} chars of [A-Za-z0-9._-])",
                    candidate, valid, CorrelationIds.MIN_LENGTH, CorrelationIds.MAX_LENGTH);
            assertFalse(valid, "expected illegal: " + candidate);
        }
    }

    @Test
    void generatedIdsAreLegalAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String generated = CorrelationIds.generate();
            assertTrue(CorrelationIds.isValid(generated), "generated id must be legal: " + generated);
            assertTrue(generated.startsWith("req-"), "generated id must be recognizable: " + generated);
            assertTrue(seen.add(generated), "generated id must be unique: " + generated);
        }
        log.info("generated 1000 ids, all legal and unique, sample='{}'", seen.iterator().next());
    }
}
