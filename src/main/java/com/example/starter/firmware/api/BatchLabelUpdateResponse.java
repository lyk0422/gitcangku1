package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量标签修改结果。整批在单事务内提交，成功后逐设备返回其替换后的规范化标签集合。
 *
 * @param requestId       幂等请求ID（按首次提交原样返回）
 * @param operator        操作者标识；未提供为 null
 * @param deviceCount     批次要求设备数（要求值）
 * @param updatedCount    实际更新设备数（实际值，成功时与 deviceCount 相同）
 * @param newTagCount     本次修订实际引入字典的新标签数（实际值，为0时字典版本不推进）
 * @param dictVersion     提交后的标签字典版本（实际值）
 * @param versionDelta    字典版本相对修订前的增量（差额，0 或 1）
 * @param revisedAtUtc    修订提交时刻，UTC，ISO-8601 格式
 * @param devices         逐设备的最终标签状态，按 deviceId 字典序稳定排序
 */
public record BatchLabelUpdateResponse(String requestId, String operator, int deviceCount, int updatedCount,
                                       int newTagCount, long dictVersion, long versionDelta,
                                       String revisedAtUtc, List<DeviceLabelsView> devices) {
}
