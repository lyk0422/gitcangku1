package com.example.starter.service;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与可选的问题刻度列表（对账拒绝时使用）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<ReconcileIssue> issues;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<ReconcileIssue> issues) {
        super(message);
        this.status = status;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 对账拒绝时的问题刻度列表；非对账场景为空列表，不生成部分结果。
     */
    public List<ReconcileIssue> issues() {
        return issues;
    }
}