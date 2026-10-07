package com.example.starter.calibration.api.dto;

/**
 * 提交参考漂移模式测量请求。必须显式引用两个当前未撤销档案：
 * t0（archiveId0 时刻）小于 t1（archiveId1 时刻），间隔不超过 1 小时，测量时刻落在 [t0, t1]。
 *
 * @param measurementKey  业务测量键，全局唯一（幂等键）
 * @param instrumentId    仪器 ID，须与两端档案一致
 * @param measuredAt      测量时刻（UTC 毫秒），ISO-8601
 * @param reading         原始读数，十进制字符串，最多 6 位小数
 * @param lowerLimit      合格下限（含端点），十进制字符串
 * @param upperLimit      合格上限（含端点），十进制字符串
 * @param submittedBy     提交人
 * @param archiveId0      下端漂移档案 ID（t0）
 * @param archiveId1      上端漂移档案 ID（t1）
 * @param requestId       全局请求 ID（可选）；提供时启用幂等去重
 * @param expectedVersion 期望版本（可选），仅作为幂等身份组成部分
 */
public record SubmitDriftMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        Long archiveId0,
        Long archiveId1,
        String requestId,
        String expectedVersion) {
}
