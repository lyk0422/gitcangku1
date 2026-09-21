package com.example.starter.plan.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态、可区分错误码与结构化明细（如时隙冲突列表）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<Map<String, Object>> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<Map<String, Object>> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<Map<String, Object>> details() {
        return details;
    }
}
