package com.example.starter.calibration.api.dto;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），
 * a/b 与温湿度范围为最多 6 位小数的十进制字符串。
 * 温湿度范围为放行时允许的环境区间，四个端点均包含在范围内。
 *
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（含），ISO-8601
 * @param validTo      有效期终点（不含），ISO-8601
 * @param a            校准系数 a，十进制字符串
 * @param b            校准偏移 b，十进制字符串
 * @param tempMin      允许环境温度下限（℃，含端点）
 * @param tempMax      允许环境温度上限（℃，含端点）
 * @param humMin       允许环境湿度下限（%RH，含端点）
 * @param humMax       允许环境湿度上限（%RH，含端点）
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String a,
        String b,
        String tempMin,
        String tempMax,
        String humMin,
        String humMax) {
}
