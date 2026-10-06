package com.example.starter.calibration.api.dto;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），数值为最多 6 位小数的十进制字符串。
 * type 缺省为 LINEAR（填 a/b）；HYSTERESIS 填 slope/upOffset/downOffset，两组参数互斥。
 *
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（含），ISO-8601
 * @param validTo      有效期终点（不含），ISO-8601
 * @param type         证书类型：LINEAR / HYSTERESIS，缺省 LINEAR
 * @param a            线性校准系数 a，十进制字符串；仅 LINEAR
 * @param b            线性校准偏移 b，十进制字符串；仅 LINEAR
 * @param slope        迟滞斜率，十进制字符串；仅 HYSTERESIS
 * @param upOffset     迟滞上升方向偏移，十进制字符串；仅 HYSTERESIS
 * @param downOffset   迟滞下降方向偏移，十进制字符串；仅 HYSTERESIS
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String type,
        String a,
        String b,
        String slope,
        String upOffset,
        String downOffset) {
}
