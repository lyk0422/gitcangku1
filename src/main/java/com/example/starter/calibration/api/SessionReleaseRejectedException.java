package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 会话批量放行被拒绝：任一会话不满足条件（未关闭、成员不合格、证书撤销、
 * 放行人撞提交人等）则整批拒绝（422），携带各项失败原因，不产生部分成功。
 */
public class SessionReleaseRejectedException extends ApiException {

    private final List<ItemFailure> failures;

    public SessionReleaseRejectedException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_BATCH_REJECTED", "会话批量放行被拒绝：存在不满足放行条件的会话");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
