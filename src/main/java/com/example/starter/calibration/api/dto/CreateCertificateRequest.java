package com.example.starter.calibration.api.dto;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），a/b 为最多 6 位小数的十进制字符串。
 *
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（含），ISO-8601
 * @param validTo         有效期终点（不含），ISO-8601
 * @param a               校准系数 a，十进制字符串
 * @param b               校准偏移 b，十进制字符串
 * @param operator        操作者（可选）；提供 requestId 时必填
 * @param requestId       全局请求 ID（可选）；提供时启用幂等去重
 * @param expectedVersion 期望版本（可选），仅作为幂等身份组成部分
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String a,
        String b,
        String operator,
        String requestId,
        String expectedVersion) {
}
