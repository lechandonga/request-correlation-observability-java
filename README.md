# Request Correlation & Observability (Java / Spring Boot)

为每个进入服务的请求分配（或沿用）唯一链路标识，并把它一路串进日志、对外响应、
线程池异步任务与定时任务；异常与慢请求纳入同一条链路，并提供健康检查入口。

## 1. 标识的沿用与生成规则

| 场景 | 行为 |
| --- | --- |
| 请求带 `X-Correlation-Id` 且合法 | **沿用**该值 |
| 请求只带 `X-Request-Id`（别名头）且合法 | **沿用**该值 |
| 主头缺失/空白，别名头存在 | 主头优先；别名头仅作回退 |
| 值非法（含空白、超长/过短、非法字符） | **丢弃并生成**新标识，绝不回显非法值 |
| 完全缺失 | **生成**新标识 |

- 合法值规则：trim 后仅含 `[A-Za-z0-9._-]`，长度 8–128。
- 生成值规则：`req-` + 32 位十六进制（UUID 去横线），保证合法且唯一。
- 无论沿用还是生成，响应头 `X-Correlation-Id` 与错误响应体 `correlationId`
  字段中都能拿到本次请求的标识；过滤器会记录判定结果与依据
  （`decision=reused|generated (reason...)`）。
- 判定与归一化逻辑见 `CorrelationFilter.resolve(...)` /
  `CorrelationIds`，单测 `CorrelationFilterResolutionTest` /
  `CorrelationIdsTest` 会打印候选值与判定依据。

## 2. 跨线程传递的边界

- **绑定方式**：标识同时写入 `ThreadLocal` 与 SLF4J MDC（key=`correlationId`），
  日志格式里以 `[corr=...]` 输出。
- **请求线程**：`CorrelationFilter`（最高优先级）在请求开始绑定，结束时
  `begin/end(previous)` 精确恢复，Tomcat 线程复用不会残留。
- **线程池 / 异步**：`CorrelationTaskDecorator` 在**任务提交时**捕获调用方标识，
  在工作线程执行前绑定、`finally` 中恢复该线程原状态：
  - 工作线程复用后不会残留上一个请求的标识；
  - 提交线程上没有任何标识时，任务获得一个**新生成**的标识（不裸奔）。
  - 默认 `@Async` / `CompletableFuture.supplyAsync(..., executor)` 使用的
    `applicationTaskExecutor`（线程名 `app-worker-*`）已装配该装饰器。
- **定时任务**：`taskScheduler`（线程名 `app-scheduler-*`）同样装饰。
  注意边界：Spring 的 `TaskDecorator` 只覆盖 `execute(Runnable)`，不覆盖
  `schedule(...)`，因此 `SchedulingConfig` 用自定义 `ScheduledThreadPoolExecutor`
  在调度提交点统一装饰；**周期任务**（`@Scheduled`）每次触发都**重新生成**标识
  （注册时装饰会冻结一个标识，属于已知陷阱，已在配置中规避）。
  定时任务本身无请求上下文，故每次触发是独立的新标识。
- **异步 Servlet 派发（async dispatch）**：当前演示端点在请求线程内等待
  worker 结果，不依赖 async dispatch；若未来使用 `Callable`/`DeferredResult`
  返回类型，需另行评估过滤器对二次派发的覆盖。

## 3. 异常、慢请求与健康检查

- **异常**：`GlobalExceptionHandler` 统一转换：
  - 对外只返回 `{status, error, message, correlationId}`，绝不泄露异常类型、
    内部消息或堆栈；
  - 服务端日志记录完整异常 + 可区分原因（异常类型）+ 标识；
  - 失败原因通过 request 属性回传给过滤器，进入统计。
- **慢请求**：耗时 ≥ `observability.slow-threshold-ms`（默认 1000ms）记 WARN
  日志并计入统计。
- **健康检查**：`GET /health` 返回
  `{status, uptimeMillis, startedAtMillis, statistics:{totalRequests,
  successfulRequests, errorRequests, slowRequests, averageDurationMillis,
  errorsByReason}}`。

## 4. 演示端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/demo/ping` | 同步调用 |
| GET | `/api/demo/async` | 提交到 worker 线程池，返回 worker 线程与标识 |
| POST | `/api/demo/fire-and-forget` | 异步任务（不等待） |
| POST | `/api/demo/scheduled` | 向调度器提交一次性任务 |
| GET | `/api/demo/slow?millis=200` | 模拟慢请求 |
| GET | `/api/demo/error?kind=business|illegal|boom` | 模拟各类异常 |
| GET | `/health` | 状态与统计 |

## 5. 本地验证

```bash
# 构建 + 全部测试（并发隔离、线程复用、归一化、异常脱敏、慢请求、定时任务）
mvn clean test

# 启动（默认随机端口，指定 8080）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# 沿用合法标识
curl -i -H 'X-Correlation-Id: trace-demo-0001' http://localhost:8080/api/demo/ping

# 非法标识会被归一化（响应头里是新生成的 req-...）
curl -i -H 'X-Correlation-Id: bad id' http://localhost:8080/api/demo/ping

# 异步传递：worker 线程日志里仍是同一个标识
curl -i -H 'X-Correlation-Id: trace-demo-0002' http://localhost:8080/api/demo/async

# 慢请求与错误
curl -i -H 'X-Correlation-Id: trace-demo-0003' 'http://localhost:8080/api/demo/slow?millis=1200'
curl -i -H 'X-Correlation-Id: trace-demo-0004' 'http://localhost:8080/api/demo/error?kind=boom'

# 健康检查（含统计）
curl -s http://localhost:8080/health | python3 -m json.tool

# 定时任务演示（每 5s 一次，每次新标识）
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --observability.heartbeat.enabled=true --observability.heartbeat.interval-ms=5000"
```

日志中每行都带 `[corr=<标识>]`，可用标识 grep 出完整链路：

```bash
grep 'corr=trace-demo-0002' app.log
```
