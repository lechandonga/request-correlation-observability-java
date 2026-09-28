package com.github.lechandonga.requestcorrelationobservabilityjava.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路标识（correlation id）的规则、校验与生成。
 *
 * <p>沿用规则：入站请求头 {@link #HEADER_NAME} 非空、去除首尾空白后长度在
 * {@value #MIN_LENGTH}~{@value #MAX_LENGTH} 之间，且只包含字母、数字、下划线与连字符时，
 * 视为合法并原样沿用；否则由服务生成新标识。</p>
 */
public final class CorrelationIds {

    /** 对外传递标识的 HTTP 请求/响应头名称。 */
    public static final String HEADER_NAME = "X-Correlation-Id";

    /** 日志 MDC 中标识的键名。 */
    public static final String MDC_KEY = "correlationId";

    /** 合法标识的最小长度。 */
    public static final int MIN_LENGTH = 8;

    /** 合法标识的最大长度。 */
    public static final int MAX_LENGTH = 64;

    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final String GENERATED_PREFIX = "cid-";

    private CorrelationIds() {
    }

    /**
     * 判断外部传入的标识是否合法可沿用。
     *
     * @param value 原始请求头值
     * @return true 表示可以沿用
     */
    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        int len = trimmed.length();
        return len >= MIN_LENGTH && len <= MAX_LENGTH && ALLOWED.matcher(trimmed).matches();
    }

    /**
     * 生成一个全新的标识，形如 {@code cid-<32位十六进制>}，自身也满足 {@link #isValid}。
     */
    public static String generate() {
        return GENERATED_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 解析入站标识：合法则沿用，缺失/非法则生成。
     *
     * @param incoming 入站请求头原值（可能为 null）
     * @return 解析结果，含最终标识、是否为服务端生成以及判定依据
     */
    public static Resolved resolve(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return new Resolved(generate(), true, "incoming correlation id is missing");
        }
        String trimmed = incoming.trim();
        if (!isValid(trimmed)) {
            return new Resolved(generate(), true,
                    "incoming correlation id is invalid: must be " + MIN_LENGTH + ".." + MAX_LENGTH
                            + " chars of [A-Za-z0-9_-]");
        }
        return new Resolved(trimmed, false, "incoming correlation id accepted");
    }

    /** 标识解析结果。 */
    public record Resolved(String correlationId, boolean generated, String reason) {
    }
}
