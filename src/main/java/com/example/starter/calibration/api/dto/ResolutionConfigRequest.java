package com.example.starter.calibration.api.dto;

/**
 * 新增/更新仪器分辨率格点配置请求。step 必须为正，origin 可为负，均为最多 6 位小数的十进制字符串。
 * expectedVersion 为乐观版本：首次创建传 0，更新传当前版本；requestId 用于幂等去重。
 *
 * @param requestId       幂等请求 ID，全局唯一；同键同参重放首次响应，异参 409，失败不占键
 * @param step            分辨率步长 s（正，十进制字符串）
 * @param origin          格点原点 o（可为负，十进制字符串）
 * @param expectedVersion 期望的当前版本；无配置时为 0
 */
public record ResolutionConfigRequest(
        String requestId,
        String step,
        String origin,
        Integer expectedVersion) {
}
