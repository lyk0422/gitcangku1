package com.example.starter.calibration.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量放行中单项失败原因。
 *
 * @param key     测量键
 * @param reasons 失败原因码列表，如 NOT_PENDING、NOT_PASSED、CERTIFICATE_REVOKED、SAME_ACTOR、
 *                MEASUREMENT_NOT_FOUND、THRESHOLD_MISSING、UNCERTAINTY_EXCEEDED
 * @param detail  失败明细（如实际值、要求值、差额）；无明细时为 null 不输出
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemFailure(String key, List<String> reasons, String detail) {

    public ItemFailure(String key, List<String> reasons) {
        this(key, reasons, null);
    }
}
