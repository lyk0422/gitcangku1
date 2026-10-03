package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码；
 * 小时额度耗尽时可携带下一可用小时（UTC，0~23）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Integer nextAvailableHour;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Integer nextAvailableHour) {
        super(message);
        this.status = status;
        this.nextAvailableHour = nextAvailableHour;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 下一可用小时（UTC，0~23）；无可用小时或不适用为 null。 */
    public Integer getNextAvailableHour() {
        return nextAvailableHour;
    }
}