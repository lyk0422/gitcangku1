package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Long nextAvailableHourStartUtc;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    /**
     * @param nextAvailableHourStartUtc 活动小时额度不可用时下一可用小时起始时刻
     *                                  （epoch 毫秒，UTC）；不适用为 null
     */
    public ApiException(HttpStatus status, String message, Long nextAvailableHourStartUtc) {
        super(message);
        this.status = status;
        this.nextAvailableHourStartUtc = nextAvailableHourStartUtc;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Long getNextAvailableHourStartUtc() {
        return nextAvailableHourStartUtc;
    }
}
