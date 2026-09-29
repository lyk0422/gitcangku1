package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 批量操作被拒绝：携带各项失败原因，不产生部分成功。
 * 默认整批业务条件不满足为 409；阈值缺失等语义前置失败为 422；版本过期为 410。
 */
public class BatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public BatchRejectedException(List<ItemFailure> failures) {
        this(HttpStatus.CONFLICT, "BATCH_REJECTED",
                "批量放行被拒绝：存在不满足放行条件的测量", failures);
    }

    public BatchRejectedException(HttpStatus status, String code, String message, List<ItemFailure> failures) {
        super(status, code, message);
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
