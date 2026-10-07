package com.example.starter.calibration.api.dto;

/**
 * 二次反解测量提交请求。仪器报告校准后信号 y，需在证书单调分支 [L, U] 内反解唯一输入根；
 * 期望输入合格区间 [lowerLimit, upperLimit] 必须位于证书输入域内。
 * 各数值为最多 6 位小数的十进制字符串。
 *
 * @param requestId       全局幂等请求 ID（非空）
 * @param measurementKey  业务测量键，全局唯一
 * @param instrumentId    仪器 ID
 * @param measuredAt      测量时刻，ISO-8601（按 UTC 归一）
 * @param signal          校准后信号 y，十进制字符串
 * @param lowerLimit      期望输入合格下界 lo（含端点），十进制字符串
 * @param upperLimit      期望输入合格上界 hi（含端点），十进制字符串
 * @param submittedBy     提交人（操作者，非空）
 * @param expectedVersion 期望版本（可选，参与幂等指纹比较）
 */
public record SubmitInverseMeasurementRequest(
        String requestId,
        String measurementKey,
        String instrumentId,
        String measuredAt,
        String signal,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        String expectedVersion) {
}
