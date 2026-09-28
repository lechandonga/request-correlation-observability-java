package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路标识规则与归一化单测。
 */
class CorrelationIdsTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdsTest.class);

    @Test
    void validIncomingIdIsReused() {
        String incoming = "trace-ABCD_1234";
        CorrelationIds.Resolved resolved = CorrelationIds.resolve(incoming);
        log.info("test=reuse incoming={} resolved={} generated={} reason={}",
                incoming, resolved.correlationId(), resolved.generated(), resolved.reason());

        assertFalse(resolved.generated(), "合法标识必须沿用");
        assertEquals(incoming, resolved.correlationId(), "合法标识必须原样沿用");
        assertTrue(CorrelationIds.isValid(resolved.correlationId()));
    }

    @Test
    void surroundingWhitespaceIsTrimmedForValidation() {
        String incoming = "  trace-ABCD_1234  ";
        CorrelationIds.Resolved resolved = CorrelationIds.resolve(incoming);
        log.info("test=trim incoming='{}' resolved={} generated={} reason={}",
                incoming, resolved.correlationId(), resolved.generated(), resolved.reason());

        assertFalse(resolved.generated());
        assertEquals("trace-ABCD_1234", resolved.correlationId());
    }

    @Test
    void missingIdIsGenerated() {
        for (String incoming : new String[]{null, "", "   "}) {
            CorrelationIds.Resolved resolved = CorrelationIds.resolve(incoming);
            log.info("test=missing incoming='{}' resolved={} generated={} reason={}",
                    incoming, resolved.correlationId(), resolved.generated(), resolved.reason());

            assertTrue(resolved.generated(), "缺失标识必须由服务生成");
            assertTrue(CorrelationIds.isValid(resolved.correlationId()),
                    "生成的标识自身必须合法");
        }
    }

    @Test
    void illegalIdsAreNormalizedToGenerated() {
        String[] illegal = {
                "short",            // 太短
                "x".repeat(65),     // 太长
                "bad id with space",// 含空格
                "bad-id!@#$",       // 含非法字符
                "中文字符标识xxxx"     // 非 ASCII
        };
        for (String incoming : illegal) {
            CorrelationIds.Resolved resolved = CorrelationIds.resolve(incoming);
            log.info("test=illegal incoming='{}' resolved={} generated={} reason={}",
                    incoming, resolved.correlationId(), resolved.generated(), resolved.reason());

            assertTrue(resolved.generated(), "非法标识必须被归一化为新标识: " + incoming);
            assertNotEquals(incoming, resolved.correlationId());
            assertTrue(CorrelationIds.isValid(resolved.correlationId()));
        }
    }

    @Test
    void generatedIdsAreUniqueAcrossManyRequests() {
        Set<String> ids = new HashSet<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            ids.add(CorrelationIds.generate());
        }
        log.info("test=uniqueness generatedCount={} uniqueCount={} reason=UUID-based generation",
                n, ids.size());
        assertEquals(n, ids.size(), "生成的标识不得重复");
    }
}
