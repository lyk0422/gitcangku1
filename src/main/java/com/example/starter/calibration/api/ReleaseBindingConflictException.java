package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * singleBatchOnly 证书跨批次引用冲突：证书首次被某放行批次引用后已绑定该批次，
 * 后续被其他批次引用即整批拒绝（422），并在各项失败中返回其已绑定的批次 ID。
 */
public class ReleaseBindingConflictException extends ApiException {

    private final List<ItemFailure> failures;

    public ReleaseBindingConflictException(List<ItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_BOUND_TO_OTHER_BATCH",
                "批量放行被拒绝：存在已绑定其他放行批次的 singleBatchOnly 证书");
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }
}
