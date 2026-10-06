package com.example.starter.calibration.api.dto;

/**
 * 创建迟滞校准会话请求。会话固定仪器与 HYSTERESIS 证书，合格区间作用于每条校正值。
 *
 * @param requestId     幂等请求键，全局唯一
 * @param sessionKey    业务会话键，全局唯一（最长 48 字符）
 * @param instrumentId  仪器 ID
 * @param certificateId 固定的 HYSTERESIS 证书 ID
 * @param lowerLimit    合格下限（含端点），最多 6 位小数的十进制字符串
 * @param upperLimit    合格上限（含端点），最多 6 位小数的十进制字符串
 * @param createdBy     会话创建人
 */
public record CreateSessionRequest(
        String requestId,
        String sessionKey,
        String instrumentId,
        Long certificateId,
        String lowerLimit,
        String upperLimit,
        String createdBy) {
}
