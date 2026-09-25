package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 放行批次环境门禁未通过：同一批任一测量缺少环境、补偿后超规格或不确定度超过批次上限时，
 * 整批拒绝（422），稳定（字典序）列出测量标识与门禁原因，既有放行状态不变。
 */
public class BatchGateException extends ApiException {

    private final List<ItemFailure> failures;

    public BatchGateException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_ENVIRONMENT_GATE",
                "放行批次未通过环境补偿门禁：存在缺环境、补偿后超规格或不确定度超限的测量");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
