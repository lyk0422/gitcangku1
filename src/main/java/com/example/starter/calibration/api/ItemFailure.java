package com.example.starter.calibration.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量放行中单项失败原因。
 *
 * @param key          测量键
 * @param reasons      失败原因码列表，如 NOT_PENDING、NOT_PASSED、CERTIFICATE_REVOKED、
 *                     CERTIFICATE_EXPIRED、CERTIFICATE_BOUND_TO_OTHER_BATCH、SAME_ACTOR、
 *                     MEASUREMENT_NOT_FOUND、TRACEABILITY_INCOMPLETE
 * @param boundBatchId singleBatchOnly 绑定冲突时，证书已绑定的其他放行批次 ID；其他场景为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemFailure(String key, List<String> reasons, String boundBatchId) {

    public ItemFailure(String key, List<String> reasons) {
        this(key, reasons, null);
    }
}
