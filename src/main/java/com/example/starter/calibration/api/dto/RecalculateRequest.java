package com.example.starter.calibration.api.dto;

/**
 * 替换标准器（重算）请求。仅对未放行批次中的测量允许；提供新的显式标准器证书版本引用，
 * 服务端生成新测量版本并以新证书重算全部补偿与不确定度。
 *
 * @param standardId         新标准器 ID
 * @param certificateVersion 新证书版本
 */
public record RecalculateRequest(
        String standardId,
        String certificateVersion) {
}
