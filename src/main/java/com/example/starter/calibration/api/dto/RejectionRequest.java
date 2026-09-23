package com.example.starter.calibration.api.dto;

/**
 * 复核驳回单项请求。
 *
 * @param position 批次内位置（从 1 开始，按原放行提交顺序）
 * @param version  驳回时该测量的版本；版本已变化则复核失败
 * @param reason   驳回原因（非空）
 */
public record RejectionRequest(Integer position, Integer version, String reason) {
}
