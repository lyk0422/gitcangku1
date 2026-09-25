package com.example.starter.calibration.api.dto;

/**
 * 提交测量请求。读数与上下限为最多 6 位小数的十进制字符串。
 *
 * <p>记录环境时 temperature 与 humidity 必须同时提供，并按仪器型号匹配当前生效的补偿系数版本；
 * 提供 instrumentModel 但温湿度超出适用区间时返回 422。uncertainty 为测量不确定度（可选）。
 *
 * @param measurementKey  业务测量键，全局唯一（幂等键）
 * @param instrumentId    仪器 ID
 * @param instrumentModel 仪器型号，用于匹配环境补偿系数版本（可空）
 * @param measuredAt      测量时刻，ISO-8601（按 UTC 归一）
 * @param reading         原始读数，十进制字符串
 * @param lowerLimit      合格下限（含端点），十进制字符串
 * @param upperLimit      合格上限（含端点），十进制字符串
 * @param temperature     环境温度（摄氏度），可空；提供时须与 humidity 同时提供
 * @param humidity        环境相对湿度（%RH），可空；提供时须与 temperature 同时提供
 * @param uncertainty     测量不确定度（与读数同量纲），可空
 * @param submittedBy     提交人
 */
public record SubmitMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String instrumentModel,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String temperature,
        String humidity,
        String uncertainty,
        String submittedBy) {
}
