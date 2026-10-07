package com.example.starter.calibration.api.dto;

/**
 * 批准拟合请求。批准人通过 X-Actor-Id 请求头提供，须不同于提交人。
 *
 * @param requestId       全局请求 ID（幂等键，非空）
 * @param expectedVersion 期望版本（可空），参与幂等参数结构化比较
 */
public record FitApproveRequest(
        String requestId,
        Long expectedVersion) {
}
