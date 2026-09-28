# Request Correlation & Observability（跨线程请求链路追踪与可观测性）

基于 Spring Boot 3 / Java 21 的示例工程：为每个进入服务的 HTTP 请求分配或沿用一个
**链路标识（correlation id）**，并把它贯穿到日志、对外响应、线程池异步任务与定时任务，
同时提供异常归一化、慢请求识别与健康统计入口。

## 1. 能力概览

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 标识沿用/生成 | `CorrelationFilter` + `CorrelationIds` | 每个请求都有唯一标识；合法则沿用，缺失/非法则生成 |
| 日志串联 | MDC（key=`correlationId`） | 所有日志自动带 `[标识]` |
| 对外回传 | 响应头 `X-Correlation-Id` + 响应体字段 | 调用方总能拿到本次请求标识 |
| 跨线程传递 | `MdcCopyingTaskDecorator` | `@Async` 线程池、调度器一次性任务自动继承，执行后还原 |
| 异常链路 | `CorrelationFilter` + `GlobalExceptionHandler` | 错误日志带标识与失败原因；响应脱敏 |
| 慢请求 | `RequestMetrics` | 耗时 ≥ 阈值输出 WARN 并计数 |
| 健康/统计 | `/health`、`/health/metrics` | 反映总量、错误、慢请求、在途等状态 |

## 2. 标识的沿用与生成规则

- 请求头：`X-Correlation-Id`。
- **沿用**：请求头非空、去除首尾空白后长度在 **8~64** 之间，且只包含
  字符集 `[A-Za-z0-9_-]`，则原样沿用。
- **生成**：请求头缺失、空白或不合法时，由服务生成形如 `cid-<32位十六进制>`（UUID 去横线）
  的新标识。生成结果自身满足上述合法规则，且全局唯一。
- **归一化是“替换”而非纠正**：非法入站标识不会被截断或改写后继续使用，而是整体换成新标识。
- 无论沿用还是生成：
  - 标识写入 SLF4J MDC，请求期间所有日志（含异常日志）都带该标识；
  - 标识通过响应头 `X-Correlation-Id` 返回，错误响应体也含 `correlationId`；
  - 请求结束（含异步请求最终 ASYNC 派发结束）后从当前线程 MDC 移除，线程复用不串号。
- 过滤器会打印一条判定日志，例如：
  - `request received correlationId=trace-xxx generated=false ... reason=incoming correlation id accepted`
  - `request received correlationId=cid-... generated=true ... reason=incoming correlation id is missing`

## 3. 跨线程传递的边界

实现类：`async.MdcCopyingTaskDecorator`（实现 Spring `TaskDecorator`），
安装在 `correlationTaskExecutor`（`@Async`/线程池）与 `correlationTaskScheduler`（定时调度器）上。

时序保证：

1. **提交时**（调用方线程）快照调用方 MDC；
2. **执行前**（工作线程）先保存工作线程原有 MDC，再写入调用方快照；
3. **执行后**（`finally`）恢复工作线程原有 MDC——原本为空则清空，任务抛异常同样执行。

因此：

- 由**请求触发**的异步工作（`CompletableFuture` 走线程池、`taskScheduler.schedule(...)` 一次性任务）：
  工作线程日志带的是**发起方请求**的标识，即使工作线程被反复复用也不会残留上一个任务的标识。
- **周期性 `@Scheduled` 任务没有发起请求**：其 MDC 为空，日志中标识为空（`-`）。
  这是预期边界，不是缺陷；上一次执行若残留过标识也会在执行后被清空。
- Servlet 层的异步请求（控制器返回 `CompletableFuture`）由容器做 REQUEST + ASYNC 两次派发。
  `CorrelationFilter` 通过 `FilterRegistrationBean` 显式注册为同时覆盖 `REQUEST` 与 `ASYNC`：
  初始派发解析标识并暂存请求属性，最终 ASYNC 派发完成计时/统计收口。
- 不使用被装饰执行器、而是自行 `new Thread(...)` 的代码**不在**自动传递范围内——
  这是刻意保留的边界：需要显式提交到配置好的执行器/调度器。

## 4. 异常、慢请求与健康检查

- 异常处理（`web.GlobalExceptionHandler`）：
  - `IllegalArgumentException` → 400 `BAD_REQUEST`；其它未捕获异常 → 500 `INTERNAL_ERROR`；
  - 响应体只含 `correlationId / status / error / message / timestamp` 固定字段，
    **不**回传异常 message、异常类型或堆栈；
  - 服务端日志记录标识与可区分原因（异常类型、耗时），便于排查。
- 慢请求：`observability.slow-threshold-ms`（默认 1000ms），达到阈值输出
  `slow request ... WARN` 并计入 `slowRequests`。
- 健康检查：
  - `GET /health`：`{"status":"UP", ...}` 存活状态；
  - `GET /health/metrics`：`totalRequests / errorRequests / slowRequests / activeRequests /
    errorRate / avgDurationMs / maxDurationMs / slowThresholdMs / recentErrors`。
    `activeRequests` 排除统计请求自身，且异步请求收口后归零。

## 5. HTTP 接口

| 方法路径 | 用途 |
| --- | --- |
| `GET /api/hello?name=` | 同步演示，响应含标识与线程名 |
| `GET /api/async` | 线程池异步演示，校验工作线程继承标识 |
| `GET /api/scheduled` | 由请求触发一个调度器一次性任务 |
| `GET /api/slow?delayMs=1500` | 慢请求演示 |
| `GET /api/error` | 抛未处理异常，验证 500 脱敏链路 |
| `GET /api/divide?a=&b=` | `b=0` 时验证 400 归一化 |
| `GET /health`、`GET /health/metrics` | 健康与统计 |

## 6. 本地验证

### 6.1 运行测试

```bash
mvn test
```

覆盖范围（共 20 个用例）：

- `CorrelationIdsTest`：合法沿用、空白裁剪、缺失生成、非法（过短/过长/空格/特殊字符/非 ASCII）归一化、万次级唯一性；
- `MdcCopyingTaskDecoratorTest`：跨线程继承、线程复用不残留、异常后仍清理、200 个并发任务标识互不串号；
- `CorrelationIntegrationTest`（MockMvc，400 并发请求）：缺失生成、端到端沿用、非法替换、
  错误响应不泄露内部信息、并发不串号、慢请求与统计；
- `AsyncCorrelationHttpTest`（真实 Tomcat 随机端口）：异步工作线程继承标识、ASYNC 派发改统计收口、
  请求触发的调度任务继承标识。

单测日志会显式打印“请求标识 + 判定依据（reason）”，可据此核验归一化行为。

### 6.2 启动服务手动验证

```bash
mvn spring-boot:run
```

另开终端：

```bash
# 1) 不带标识：观察响应头生成 cid-...
curl -i http://localhost:8080/api/hello

# 2) 带合法标识：应原样回传
curl -i -H 'X-Correlation-Id: trace-manual-001' http://localhost:8080/api/hello

# 3) 带非法标识（含空格/特殊字符）：应被替换为 cid-...
curl -i -H 'X-Correlation-Id: bad id!!' http://localhost:8080/api/hello

# 4) 异步：工作线程日志与响应中的 asyncCorrelationId 都等于传入标识
curl -s -H 'X-Correlation-Id: trace-async-001' http://localhost:8080/api/async

# 5) 异常：返回脱敏错误体，但 correlationId 可对账
curl -s -i -H 'X-Correlation-Id: trace-error-001' http://localhost:8080/api/error

# 6) 慢请求
curl -s 'http://localhost:8080/api/slow?delayMs=1500'

# 7) 健康与统计
curl -s http://localhost:8080/health
curl -s http://localhost:8080/health/metrics
```

观察服务端控制台：每条日志前缀都带 `[correlationId]`；异步任务日志线程名是
`corr-async-*` 但标识与请求一致；周期性心跳（`corr-sched-*`）标识为空。

## 7. 关键配置

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `observability.slow-threshold-ms` | `1000` | 慢请求阈值（毫秒） |
| `observability.heartbeat.fixed-delay-ms` | `60000` | 心跳定时任务周期 |

日志格式见 `src/main/resources/logback-spring.xml`（`%X{correlationId:-}` 输出 MDC 标识）。
