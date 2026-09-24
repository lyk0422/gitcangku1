package com.example.starter.calibration.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量放行中单项失败原因。
 *
 * @param key              测量键
 * @param reasons          失败原因码列表，如 NOT_PENDING、NOT_PASSED、CERTIFICATE_REVOKED、
 *                         SAME_ACTOR、MEASUREMENT_NOT_FOUND、UNDER_INTERIM_ISOLATION
 * @param blockingCheckKey 触发隔离的 FAIL 核查键；仅 UNDER_INTERIM_ISOLATION 时输出
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemFailure(String key, List<String> reasons, String blockingCheckKey) {

    public ItemFailure(String key, List<String> reasons) {
        this(key, reasons, null);
    }
}
