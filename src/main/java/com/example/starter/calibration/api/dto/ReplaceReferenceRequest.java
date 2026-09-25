package com.example.starter.calibration.api.dto;

/**
 * 替换标准器请求：对未放行批次整体切换到新的校准证书并重算全部不确定度。
 *
 * @param certificateId 新校准证书 ID
 */
public record ReplaceReferenceRequest(Long certificateId) {
}
