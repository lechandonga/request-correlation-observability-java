package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实 Servlet 容器（随机端口）的异步链路集成测试。
 *
 * <p>必须用真实容器：异步请求完成后的 ASYNC 派发由容器驱动并经过过滤器，
 * MockMvc 的 asyncDispatch 默认不经过过滤器链。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "observability.slow-threshold-ms=200")
class AsyncCorrelationHttpTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncCorrelationHttpTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void correlationIdPropagatesToAsyncWorkerAndCompletes() throws Exception {
        String incoming = "trace-http-async-01";
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIds.HEADER_NAME, incoming);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/async"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        JsonNode body = JSON.readTree(response.getBody());
        String headerCid = response.getHeaders().getFirst(CorrelationIds.HEADER_NAME);

        log.info("test=httpAsync incoming={} headerCorrelationId={} requestCorrelationId={} "
                + "asyncCorrelationId={} requestThread={} workerThread={} "
                + "reason=real container runs filter on ASYNC dispatch",
                incoming, headerCid, body.path("requestCorrelationId").asText(),
                body.path("asyncCorrelationId").asText(), body.path("requestThread").asText(),
                body.path("workerThread").asText());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(incoming, headerCid);
        assertEquals(incoming, body.path("requestCorrelationId").asText());
        assertEquals(incoming, body.path("asyncCorrelationId").asText(),
                "异步工作线程必须沿用发起方标识");
        assertNotEquals(body.path("requestThread").asText(), body.path("workerThread").asText());

        // 真实容器 ASYNC 派发经过过滤器，统计应收口：在途数归零
        ResponseEntity<String> metrics = restTemplate.getForEntity(url("/health/metrics"),
                String.class);
        JsonNode m = JSON.readTree(metrics.getBody());
        log.info("test=httpAsync.metrics total={} active={} reason=active must be 0 after "
                + "async completion", m.path("totalRequests").asLong(),
                m.path("activeRequests").asLong());
        assertEquals(0, m.path("activeRequests").asLong(), "异步请求完成后在途数必须归零");
        assertTrue(m.path("totalRequests").asLong() >= 2);
    }

    @Test
    void scheduledOneShotInheritsCorrelationId() throws Exception {
        String incoming = "trace-http-sched-01";
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIds.HEADER_NAME, incoming);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/scheduled"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode body = JSON.readTree(response.getBody());
        log.info("test=httpScheduled headerCorrelationId={} bodyCorrelationId={} "
                + "reason=request-triggered scheduler task should inherit id",
                response.getHeaders().getFirst(CorrelationIds.HEADER_NAME),
                body.path("correlationId").asText());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(incoming, body.path("correlationId").asText());
        assertFalse(body.path("correlationId").asText().isBlank());
        // 给调度器线程执行一次性任务留出时间（其日志在服务端输出，验证装饰器不残留）
        Thread.sleep(200);
    }
}
