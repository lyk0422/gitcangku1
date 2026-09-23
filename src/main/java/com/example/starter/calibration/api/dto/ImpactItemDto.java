package com.example.starter.calibration.api.dto;

/**
 * 失效影响明细响应：每条已放行结果冻结的到失效根最短血缘路径。
 *
 * @param measurementKey 受影响测量键
 * @param standardId     结果绑定的标准器版本业务键
 * @param path           冻结的最短血缘路径（standardId 以 &gt; 连接，等长取字典序）
 * @param previousStatus 冻结前状态：RELEASED
 */
public record ImpactItemDto(
        String measurementKey,
        String standardId,
        String path,
        String previousStatus) {
}
