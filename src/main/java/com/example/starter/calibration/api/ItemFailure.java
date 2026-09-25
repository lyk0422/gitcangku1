package com.example.starter.calibration.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量放行／批量提交／整批重算中单项失败原因。
 *
 * @param key          测量键
 * @param reasons      失败原因码列表，如 NOT_PENDING、NOT_PASSED、CERTIFICATE_REVOKED、
 *                     CERTIFICATE_EXPIRED、CERTIFICATE_BOUND_TO_OTHER_BATCH、SAME_ACTOR、
 *                     MEASUREMENT_NOT_FOUND、NO_MATCHING_CERTIFICATE、INVALID_INPUT、
 *                     REFERENCE_KEY_CONFLICT、DUPLICATE_MEASUREMENT_KEY、
 *                     INSTRUMENT_MISMATCH、CERTIFICATE_NOT_VALID_AT_MEASURED_AT
 * @param boundBatchId 仅 CERTIFICATE_BOUND_TO_OTHER_BATCH 时返回：证书已绑定的放行批次 ID
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemFailure(String key, List<String> reasons, String boundBatchId) {

    public ItemFailure(String key, List<String> reasons) {
        this(key, reasons, null);
    }
}
