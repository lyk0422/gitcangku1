package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 会话批量放行被拒绝：任一会话不满足条件（未关闭、空会话、成员不合格、证书撤销、放行人冲突等）
 * 则整批拒绝（422），携带各会话失败原因，不产生部分成功。
 */
public class SessionBatchRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public SessionBatchRejectedException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_BATCH_REJECTED", "会话批量放行被拒绝：存在不满足放行条件的会话");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
