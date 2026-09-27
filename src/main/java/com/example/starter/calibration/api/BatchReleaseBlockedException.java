package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 批次放行被阻断（422）：当前修订版本存在未裁决离群值，携带各离群记录的实际偏差与允许偏差。
 */
public class BatchReleaseBlockedException extends ApiException {

    private final List<ItemFailure> failures;

    public BatchReleaseBlockedException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "OUTLIERS_UNRESOLVED",
                "批次放行被阻断：存在未裁决的离群记录");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
