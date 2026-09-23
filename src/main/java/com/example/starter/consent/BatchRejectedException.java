package com.example.starter.consent;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.example.starter.consent.dto.BatchFailureItem;

/**
 * 批量查询整批拒绝异常：携带输入顺序首个失败项决定的 HTTP 状态，以及全部失败项的索引与稳定错误码。
 *
 * <p>错误体不含任何记录 payload。
 */
public class BatchRejectedException extends RuntimeException {

    private final transient List<BatchFailureItem> failures;
    private final HttpStatus status;

    public BatchRejectedException(HttpStatus status, List<BatchFailureItem> failures) {
        super("批量查询存在失败项");
        this.status = status;
        this.failures = List.copyOf(failures);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<BatchFailureItem> getFailures() {
        return failures;
    }
}
