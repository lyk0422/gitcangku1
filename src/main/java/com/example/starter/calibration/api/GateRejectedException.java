package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 补偿门禁批次被拒绝：带不确定度上限放行时，整批任一测量缺少环境、补偿后超规格
 * 或不确定度超过批次上限，则整次 422，稳定（按测量标识字典序）列出失败项与原因，
 * 事务回滚，既有放行状态不变。
 */
public class GateRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public GateRejectedException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_GATE_REJECTED",
                "补偿门禁未通过：存在缺少环境、补偿后超规格或不确定度超限的测量");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
