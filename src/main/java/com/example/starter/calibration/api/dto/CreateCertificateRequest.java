package com.example.starter.calibration.api.dto;

/**
 * 创建校准标准器证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），
 * a/b/uncertainty 为十进制字符串。
 *
 * <p>standardId/version 显式标识证书版本；未提供 standardId 时取 instrumentId，
 * 未提供 version 时默认 "v1"。uncertainty 默认 "0"、uncertaintyVersion 默认 "v1"、
 * singleBatchOnly 默认 false。
 *
 * @param standardId         标准器 ID；为空时取 instrumentId
 * @param instrumentId       仪器/被测对象 ID（历史兼容字段）
 * @param version            证书版本；为空时默认 v1，同一标准器内唯一
 * @param validFrom          有效期起点（含），ISO-8601
 * @param validTo            有效期终点（不含），ISO-8601
 * @param a                  补偿系数 a，十进制字符串
 * @param b                  补偿偏移 b，十进制字符串
 * @param uncertainty        标准器标准不确定度，非负十进制字符串；为空默认 0
 * @param uncertaintyVersion 不确定度版本；为空默认 v1
 * @param singleBatchOnly    是否仅允许单个放行批次引用；为空默认 false
 */
public record CreateCertificateRequest(
        String standardId,
        String instrumentId,
        String version,
        String validFrom,
        String validTo,
        String a,
        String b,
        String uncertainty,
        String uncertaintyVersion,
        Boolean singleBatchOnly) {
}
