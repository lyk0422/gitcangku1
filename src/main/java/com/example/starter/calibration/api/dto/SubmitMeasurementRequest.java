package com.example.starter.calibration.api.dto;

/**
 * 提交测量请求。读数与上下限为最多 6 位小数的十进制字符串。
 * 环境与不确定度可选：temperatureC/humidityPct/instrumentModel 必须同时出现才视为记录环境。
 *
 * @param measurementKey  业务测量键，全局唯一（幂等键）
 * @param instrumentId    仪器 ID
 * @param instrumentModel 仪器型号；记录环境时必填，用于匹配环境补偿系数版本
 * @param measuredAt      测量时刻，ISO-8601（按 UTC 归一）
 * @param reading         原始读数，十进制字符串
 * @param lowerLimit      合格下限（含端点），十进制字符串
 * @param upperLimit      合格上限（含端点），十进制字符串
 * @param temperatureC   环境温度（摄氏度，最多 4 位小数）；与湿度、型号同时提供
 * @param humidityPct    环境相对湿度（%RH，最多 4 位小数）；与温度、型号同时提供
 * @param uncertainty    扩展不确定度（非负，最多 6 位小数）；可空
 * @param submittedBy     提交人
 * @param calcKey       幂等键；同键同指纹重放首次成功结果，失败不占键；可空
 */
public record SubmitMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String instrumentModel,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String temperatureC,
        String humidityPct,
        String uncertainty,
        String submittedBy,
        String calcKey) {
}
