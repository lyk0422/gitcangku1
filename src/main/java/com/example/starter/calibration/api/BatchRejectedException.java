package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 批量操作被整批拒绝：任一项校验失败则整批拒绝（409），携带各项失败原因，不产生部分成功。
 * 适用于批量放行、复核驳回与重新放行。
 */
public class BatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public BatchRejectedException(List<ItemFailure> failures) {
        this("BATCH_REJECTED", "批量放行被拒绝：存在不满足放行条件的测量", failures);
    }

    public BatchRejectedException(String code, String message, List<ItemFailure> failures) {
        super(HttpStatus.CONFLICT, code, message);
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
