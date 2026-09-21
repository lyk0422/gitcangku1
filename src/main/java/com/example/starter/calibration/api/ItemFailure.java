package com.example.starter.calibration.api;

import java.util.List;

/**
 * 批量放行中单项失败原因。
 *
 * @param key     测量键
 * @param reasons 失败原因码列表，如 NOT_PENDING、NOT_PASSED、CERTIFICATE_REVOKED、SAME_ACTOR、MEASUREMENT_NOT_FOUND
 */
public record ItemFailure(String key, List<String> reasons) {
}
