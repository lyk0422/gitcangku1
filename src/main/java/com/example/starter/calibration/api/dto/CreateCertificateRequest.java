package com.example.starter.calibration.api.dto;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），a/b 为最多 6 位小数的十进制字符串。
 * 温湿度允许范围四个字段要么全部提供（环境补偿放行必须），要么全部省略；端点均包含。
 *
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（含），ISO-8601
 * @param validTo      有效期终点（不含），ISO-8601
 * @param a            校准系数 a，十进制字符串
 * @param b            校准偏移 b，十进制字符串
 * @param tempMin      允许温度下限（℃，含端点），可空
 * @param tempMax      允许温度上限（℃，含端点），可空
 * @param humidityMin  允许湿度下限（%RH，含端点），可空
 * @param humidityMax  允许湿度上限（%RH，含端点），可空
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String a,
        String b,
        String tempMin,
        String tempMax,
        String humidityMin,
        String humidityMax) {
}
