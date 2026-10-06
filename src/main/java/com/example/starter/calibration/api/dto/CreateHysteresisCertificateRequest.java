package com.example.starter.calibration.api.dto;

/**
 * 创建迟滞校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），
 * slope/upOffset/downOffset 为最多 6 位小数的十进制字符串。
 *
 * @param requestId    幂等请求 ID（同键同参重放首次响应，异参 409）
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（含），ISO-8601
 * @param validTo      有效期终点（不含），ISO-8601
 * @param slope        迟滞斜率，十进制字符串
 * @param upOffset     上升方向偏移，十进制字符串
 * @param downOffset   下降方向偏移，十进制字符串
 */
public record CreateHysteresisCertificateRequest(
        String requestId,
        String instrumentId,
        String validFrom,
        String validTo,
        String slope,
        String upOffset,
        String downOffset) {
}
