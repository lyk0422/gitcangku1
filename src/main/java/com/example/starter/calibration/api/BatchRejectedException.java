package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 批量操作被拒绝：任一项校验失败则整批拒绝，携带各项失败原因，不产生部分成功。
 * 批量放行默认 409；绑定冲突等场景可指定 422 与可区分错误码。
 */
public class BatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public BatchRejectedException(List<ItemFailure> failures) {
        this(failures, HttpStatus.CONFLICT, "BATCH_REJECTED", "批量放行被拒绝：存在不满足放行条件的测量");
    }

    public BatchRejectedException(List<ItemFailure> failures, HttpStatus status, String code, String message) {
        super(status, code, message);
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
