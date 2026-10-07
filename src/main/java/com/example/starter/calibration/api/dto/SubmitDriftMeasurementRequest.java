package com.example.starter.calibration.api.dto;

/**
 * 提交参考漂移模式测量请求。必须显式引用两份当前未撤销的漂移档案 t0、t1：
 * t0 早于 t1、间隔不超过 1 小时、测量时刻 t 落在 [t0, t1]；校正偏移为线性时间插值。
 * 写操作使用全局 requestId 幂等。
 *
 * @param requestId       全局请求 ID（幂等键），非空
 * @param operator        操作者（即提交人），非空
 * @param measurementKey  业务测量键，全局唯一
 * @param instrumentId    仪器 ID，须与两份档案一致
 * @param measuredAt      测量时刻，ISO-8601（按 UTC 归一，毫秒精度）
 * @param reading         原始读数，十进制字符串，最多 6 位小数
 * @param lowerLimit      合格下限（含端点），十进制字符串
 * @param upperLimit      合格上限（含端点），十进制字符串
 * @param profileT0Id     下界漂移档案 ID
 * @param profileT1Id     上界漂移档案 ID
 * @param expectedVersion 期望版本（可选，参与幂等身份指纹）
 */
public record SubmitDriftMeasurementRequest(
        String requestId,
        String operator,
        String measurementKey,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        Long profileT0Id,
        Long profileT1Id,
        Long expectedVersion) {
}
