package com.example.starter.calibration.api.dto;

/**
 * 联合放行批次明细响应项：放行时固化的测量标识、证书与未舍入计算值快照。
 *
 * @param measurementId 测量记录 ID
 * @param measurementKey 测量键快照
 * @param certificateId 放行时使用的校准证书 ID 快照
 * @param computedValue 放行时未舍入计算值快照（去尾零十进制字符串）
 */
public record JointReleaseItemResponse(long measurementId, String measurementKey,
                                       long certificateId, String computedValue) {
}
