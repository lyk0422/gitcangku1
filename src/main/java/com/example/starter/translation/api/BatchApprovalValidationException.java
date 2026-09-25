package com.example.starter.translation.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 批量审核整批不通过异常：HTTP 422，携带逐条原因，整批不批准任何一条。
 */
public class BatchApprovalValidationException extends ApiException {

    private final transient List<ApiDtos.BatchApprovalItemError> itemErrors;

    public BatchApprovalValidationException(List<ApiDtos.BatchApprovalItemError> itemErrors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_APPROVAL_REJECTED", "批量审核存在未通过条目，整批未批准");
        this.itemErrors = List.copyOf(itemErrors);
    }

    /** 逐条失败原因（稳定顺序）。 */
    public List<ApiDtos.BatchApprovalItemError> itemErrors() {
        return itemErrors;
    }
}
