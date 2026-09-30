package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码；
 * 活动小时预算耗尽（429）时可携带下一可用小时起点。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Long nextAvailableHourStartUtc;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Long nextAvailableHourStartUtc) {
        super(message);
        this.status = status;
        this.nextAvailableHourStartUtc = nextAvailableHourStartUtc;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 下一可用小时起点（epoch 毫秒，UTC，左闭右开）；无可用小时或不适用为 null。 */
    public Long getNextAvailableHourStartUtc() {
        return nextAvailableHourStartUtc;
    }
}
