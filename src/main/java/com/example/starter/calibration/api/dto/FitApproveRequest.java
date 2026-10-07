package com.example.starter.calibration.api.dto;

/**
 * 拟合批准请求体（可空 body）。批准人通过 X-Actor-Id 请求头提供，须不同于提交人。
 *
 * @param requestId 全局请求 ID（可选幂等键）；提供时按 操作+资源+全参数 参与去重，
 *                  同参重放首次批准响应，异参 409；不提供时由拟合状态保证批准只生效一次
 */
public record FitApproveRequest(String requestId) {
}
