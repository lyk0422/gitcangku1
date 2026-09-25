package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 联合批次放行校验未通过：任一条测量不满足放行条件则整批拒绝（422），
 * 携带逐条失败原因，不放行任何一条。与并发放行冲突（409）区分。
 */
public class JointBatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public JointBatchRejectedException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "JOINT_BATCH_REJECTED",
                "联合批次放行被拒绝：存在不满足放行条件的测量");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
