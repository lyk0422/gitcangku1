package com.example.starter.consent;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.example.starter.consent.dto.BatchItemFailure;

/**
 * 批量查询整批拒绝异常：携带全部失败项及整体 HTTP 状态（输入顺序首个失败项的状态）。
 */
public class BatchQueryException extends RuntimeException {

    private final HttpStatus status;
    private final List<BatchItemFailure> failures;

    public BatchQueryException(HttpStatus status, List<BatchItemFailure> failures) {
        super("批量查询存在 " + failures.size() + " 个失败项");
        this.status = status;
        this.failures = List.copyOf(failures);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<BatchItemFailure> getFailures() {
        return failures;
    }
}
