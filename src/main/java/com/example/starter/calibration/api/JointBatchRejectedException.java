package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 联合批次放行被拒绝：任一项校验失败则整批拒绝（422），携带各项失败原因，不产生部分放行。
 */
public class JointBatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public JointBatchRejectedException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "JOINT_BATCH_REJECTED", "联合批次放行被拒绝：存在不满足放行条件的测量");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
