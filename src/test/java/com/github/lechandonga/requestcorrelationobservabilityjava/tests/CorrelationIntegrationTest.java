package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端链路追踪集成测试：过滤器 → 控制器 → 异步线程池 → 异常处理 → 统计。
 * 测试环境把慢请求阈值调低到 200ms。
 */
@SpringBootTest(properties = "observability.slow-threshold-ms=200")
@AutoConfigureMockMvc
class CorrelationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingCorrelationIdIsGeneratedAndReturned() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andReturn();

        String headerCid = result.getResponse().getHeader(CorrelationIds.HEADER_NAME);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        String bodyCid = body.path("correlationId").asText();

        log.info("test=missingHeader headerCorrelationId={} bodyCorrelationId={} "
                + "reason=no incoming header -> server generated", headerCid, bodyCid);

        assertTrue(headerCid != null && headerCid.startsWith("cid-"),
                "缺失标识时响应头必须返回服务生成的标识");
        assertEquals(headerCid, bodyCid, "响应头与响应体中的标识必须一致");
        assertTrue(CorrelationIds.isValid(headerCid));

        // MockMvc 同步执行过滤器链：返回后调用线程 MDC 必须已被清理
        assertNull(MDC.get(CorrelationIds.MDC_KEY), "请求结束后线程 MDC 必须清理");
    }

    @Test
    void validCorrelationIdIsReusedEndToEnd() throws Exception {
        String incoming = "trace-client-reuse-01";
        MvcResult result = mockMvc
                .perform(get("/api/hello").header(CorrelationIds.HEADER_NAME, incoming))
                .andExpect(status().isOk())
                .andReturn();

        String headerCid = result.getResponse().getHeader(CorrelationIds.HEADER_NAME);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        log.info("test=reuseE2E incoming={} headerCorrelationId={} bodyCorrelationId={} "
                + "reason=valid header accepted and echoed", incoming, headerCid,
                body.path("correlationId").asText());

        assertEquals(incoming, headerCid);
        assertEquals(incoming, body.path("correlationId").asText());
    }

    @Test
    void invalidCorrelationIdIsReplaced() throws Exception {
        String incoming = "bad id!!";
        MvcResult result = mockMvc
                .perform(get("/api/hello").header(CorrelationIds.HEADER_NAME, incoming))
                .andExpect(status().isOk())
                .andReturn();

        String headerCid = result.getResponse().getHeader(CorrelationIds.HEADER_NAME);
        log.info("test=invalidE2E incoming='{}' headerCorrelationId={} "
                + "reason=illegal header normalized to generated id", incoming, headerCid);

        assertNotEquals(incoming, headerCid);
        assertTrue(headerCid.startsWith("cid-"));
    }

    @Test
    void errorResponseCarriesCorrelationIdButLeaksNoInternals() throws Exception {
        String incoming = "trace-error-case-01";
        MvcResult result = mockMvc
                .perform(get("/api/error").header(CorrelationIds.HEADER_NAME, incoming))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String raw = result.getResponse().getContentAsString();
        JsonNode body = JSON.readTree(raw);
        String headerCid = result.getResponse().getHeader(CorrelationIds.HEADER_NAME);

        log.info("test=errorMasking incoming={} headerCorrelationId={} bodyCorrelationId={} "
                + "errorCode={} reason=500 keeps id but hides internals",
                incoming, headerCid, body.path("correlationId").asText(),
                body.path("error").asText());

        assertEquals(incoming, headerCid, "出错时仍必须回传标识");
        assertEquals(incoming, body.path("correlationId").asText());
        assertEquals(500, body.path("status").asInt());
        assertEquals("INTERNAL_ERROR", body.path("error").asText());
        assertFalse(raw.contains("simulated failure"), "响应不得泄露内部异常消息");
        assertFalse(raw.contains("IllegalStateException"), "响应不得泄露异常类型");
        assertFalse(raw.contains("at com.github"), "响应不得包含堆栈");
    }

    @Test
    void badRequestIsNormalizedWithoutLeakingDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/divide").param("a", "1").param("b", "0"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String raw = result.getResponse().getContentAsString();
        JsonNode body = JSON.readTree(raw);
        log.info("test=badRequest bodyCorrelationId={} errorCode={} reason=400 normalized",
                body.path("correlationId").asText(), body.path("error").asText());

        assertEquals(400, body.path("status").asInt());
        assertEquals("BAD_REQUEST", body.path("error").asText());
        assertFalse(raw.contains("divisor"), "响应不得泄露内部校验消息");
        assertTrue(body.path("correlationId").asText().startsWith("cid-"));
    }

    @Test
    void concurrentRequestsNeverMixCorrelationIds() throws Exception {
        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads * perThread);
        AtomicInteger failures = new AtomicInteger();
        Map<Integer, String> mismatchDetails = new ConcurrentHashMap<>();

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int seq = threadIndex * perThread + i;
                        // 一半带合法标识，一半不带（走生成）
                        String incoming = (seq % 2 == 0)
                                ? String.format("trace-conc-%06d", seq) : null;
                        MvcResult result = mockMvc.perform(get("/api/hello")
                                        .header(CorrelationIds.HEADER_NAME, incoming == null ? "" : incoming))
                                .andExpect(status().isOk())
                                .andReturn();
                        String returned = result.getResponse().getHeader(CorrelationIds.HEADER_NAME);
                        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
                        String expected = incoming != null ? incoming : returned;
                        if (!expected.equals(returned)
                                || !returned.equals(body.path("correlationId").asText())) {
                            failures.incrementAndGet();
                            mismatchDetails.put(seq,
                                    "incoming=" + incoming + " returned=" + returned + " body="
                                            + body.path("correlationId").asText());
                        }
                        done.countDown();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    mismatchDetails.put(threadIndex, e.toString());
                    while (done.getCount() > 0) {
                        done.countDown();
                    }
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        log.info("test=concurrentE2E total={} failures={} mismatches={} "
                + "reason=each concurrent request keeps its own id",
                threads * perThread, failures.get(), mismatchDetails);

        assertTrue(finished, "并发请求必须在超时前全部完成");
        assertEquals(0, failures.get(), "并发请求之间标识不得串号: " + mismatchDetails);
    }

    @Test
    void slowRequestIsCountedAndMetricsExposed() throws Exception {
        // 300ms > 测试阈值 200ms，应被计为慢请求
        mockMvc.perform(get("/api/slow").param("delayMs", "300"))
                .andExpect(status().isOk());

        MvcResult health = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode healthBody = JSON.readTree(health.getResponse().getContentAsString());
        log.info("test=health status={} reason=liveness endpoint", healthBody.path("status").asText());
        assertEquals("UP", healthBody.path("status").asText());

        MvcResult metrics = mockMvc.perform(get("/health/metrics"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode m = JSON.readTree(metrics.getResponse().getContentAsString());
        log.info("test=metrics total={} errors={} slow={} active={} slowThresholdMs={} "
                + "reason=metrics endpoint reflects runtime state",
                m.path("totalRequests").asLong(), m.path("errorRequests").asLong(),
                m.path("slowRequests").asLong(), m.path("activeRequests").asLong(),
                m.path("slowThresholdMs").asLong());

        assertTrue(m.path("slowRequests").asLong() >= 1, "慢请求必须被统计");
        assertEquals(0, m.path("activeRequests").asLong(), "请求结束后在途数应归零");
        assertEquals(200, m.path("slowThresholdMs").asLong());
    }

    @Test
    void failedRequestsAreCountedInMetrics() throws Exception {
        mockMvc.perform(get("/api/error")).andExpect(status().isInternalServerError());
        mockMvc.perform(get("/api/divide").param("a", "1").param("b", "0"))
                .andExpect(status().isBadRequest());

        MvcResult metrics = mockMvc.perform(get("/health/metrics")).andReturn();
        JsonNode m = JSON.readTree(metrics.getResponse().getContentAsString());
        log.info("test=errorMetrics total={} errors={} recentErrors={} "
                + "reason=only 5xx counts as error; 400 is client error",
                m.path("totalRequests").asLong(), m.path("errorRequests").asLong(),
                m.path("recentErrors").size());

        assertTrue(m.path("errorRequests").asLong() >= 1, "500 错误必须计入 errorRequests");
    }
}
