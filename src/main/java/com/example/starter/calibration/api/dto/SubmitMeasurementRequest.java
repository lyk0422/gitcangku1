package com.example.starter.calibration.api.dto;

/**
 * 提交测量请求。读数与上下限为最多 6 位小数的十进制字符串。
 * 匹配二次证书时：reading 为校准后信号 y，lowerLimit/upperLimit 为期望输入合格区间 [lo,hi]，
 * 必须位于证书输入闭区间 [L,U] 内。
 * requestId 非空时启用幂等去重（操作者为 submittedBy，必须非空）。
 *
 * @param measurementKey  业务测量键，全局唯一（幂等键）
 * @param instrumentId    仪器 ID
 * @param measuredAt      测量时刻，ISO-8601（按 UTC 归一）
 * @param reading         原始读数（线性）或信号 y（二次），十进制字符串
 * @param lowerLimit      合格下限（含端点），十进制字符串；二次模式为输入区间下界 lo
 * @param upperLimit      合格上限（含端点），十进制字符串；二次模式为输入区间上界 hi
 * @param submittedBy     提交人
 * @param requestId       全局幂等请求 ID；为空则不启用幂等
 * @param expectedVersion 期望版本，参与幂等指纹结构化比较
 */
public record SubmitMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        String requestId,
        String expectedVersion) {
}
