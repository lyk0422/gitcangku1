package com.example.starter.calibration.api.dto;

/**
 * 创建迟滞校准会话请求。会话固定仪器与一张未撤销的 HYSTERESIS 证书。
 *
 * @param requestId     幂等请求 ID
 * @param instrumentId  仪器 ID，须与证书仪器一致
 * @param certificateId 固定的 HYSTERESIS 证书 ID
 */
public record CreateSessionRequest(
        String requestId,
        String instrumentId,
        Long certificateId) {
}
