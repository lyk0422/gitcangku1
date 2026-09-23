package com.example.starter.calibration.api.dto;

/**
 * 创建后继修订请求。仅允许修改测量值（原始读数）及说明，其余原始输入与证书保持不变。
 *
 * @param measurementKey 被驳回（REJECTED）测量的业务测量键
 * @param reading        修订后的原始读数，十进制字符串（最多 6 位小数）
 * @param note           修订说明（可为空）
 */
public record CreateRevisionRequest(String measurementKey, String reading, String note) {
}
