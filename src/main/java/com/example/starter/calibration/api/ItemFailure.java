package com.example.starter.calibration.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量放行中单项失败原因。
 *
 * @param key      测量键
 * @param revision 版本化放行时请求的版本号；旧放行入口为 null（不输出）
 * @param reasons  失败原因码列表，如 NOT_PENDING、NOT_PASSED、CERTIFICATE_REVOKED、
 *                 SAME_ACTOR、MEASUREMENT_NOT_FOUND、REVISION_MISMATCH、REVISION_REQUIRED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemFailure(String key, Integer revision, List<String> reasons) {

    /** 旧放行入口（仅按测量键）使用的构造器。 */
    public ItemFailure(String key, List<String> reasons) {
        this(key, null, reasons);
    }
}
