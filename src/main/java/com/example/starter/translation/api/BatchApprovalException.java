package com.example.starter.translation.api;

import java.util.List;

/**
 * 批量审核校验异常：整批不批准，携带逐条失败原因，由全局异常处理器转换为 422 响应。
 */
public class BatchApprovalException extends RuntimeException {

    private final List<ApiDtos.BatchItemFailure> failures;

    public BatchApprovalException(List<ApiDtos.BatchItemFailure> failures) {
        super("批量审核未通过，共 " + failures.size() + " 条译文不满足条件");
        this.failures = List.copyOf(failures);
    }

    public List<ApiDtos.BatchItemFailure> failures() {
        return failures;
    }
}
