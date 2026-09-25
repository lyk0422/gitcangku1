package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码与机器可读的失败原因码。
 *
 * <p>原因码用于调用方区分不同失败（如 CONSENT_DENIED、SILENCE_PERIOD、FREQUENCY_LIMIT、
 * BUDGET_EXHAUSTED 等）；历史两参构造以原因码缺省保持兼容。</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    public ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public ApiException(HttpStatus status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 机器可读失败原因码；未指定时为 null。 */
    public String getReason() {
        return reason;
    }
}
