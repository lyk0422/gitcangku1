package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 批量放行被拒绝：任一项校验失败则整批拒绝，携带各项失败原因，不产生部分成功。
 * 含复核门禁失败（缺少或无效 PASS 复核）时整体为 422，其余校验失败为 409。
 */
public class BatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public BatchRejectedException(List<ItemFailure> failures) {
        this(failures, HttpStatus.CONFLICT, "批量放行被拒绝：存在不满足放行条件的测量");
    }

    public BatchRejectedException(List<ItemFailure> failures, HttpStatus status, String message) {
        super(status, "BATCH_REJECTED", message);
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
