package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 批量放行被拒绝：任一项校验失败则整批拒绝（409），携带各项失败原因，不产生部分成功。
 */
public class BatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public BatchRejectedException(List<ItemFailure> failures) {
        super(HttpStatus.CONFLICT, "BATCH_REJECTED", "批量放行被拒绝：存在不满足放行条件的测量");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
