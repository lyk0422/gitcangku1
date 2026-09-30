package com.example.starter.archival.web;

/**
 * 请求与当前业务状态冲突，映射 HTTP 409，例如已封存批次被修改、异参重放、跨批次占用、并发双入组。
 */
public class ApiConflictException extends RuntimeException {

    public ApiConflictException(String message) {
        super(message);
    }
}
