package com.example.starter.calibration.api.dto;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），a/b 为最多 6 位小数的十进制字符串。
 *
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（含），ISO-8601
 * @param validTo      有效期终点（不含），ISO-8601
 * @param a            校准系数 a，十进制字符串
 * @param b            校准偏移 b，十进制字符串
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String a,
        String b) {
}
