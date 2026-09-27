package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 已让渡占用重新申请请求。原占用必须为 YIELDED；必须用重新审查后的新 CLEAR
 * 审查显式申请。新占用为全新 ACTIVE 常规占用，原占用历史保持不变；
 * 若新时空窗仍命中有效走廊则返回 409。
 *
 * @param occupancyId       新占用唯一标识（客户端指定）
 * @param parentOccupancyId 原已让渡占用标识
 * @param reviewId          重新审查后的新 CLEAR 审查标识
 * @param startUtc          新占用起点 epoch 毫秒（UTC，含）
 * @param endUtc            新占用终点 epoch 毫秒（UTC，不含）
 * @param altMin            最低高度（含），米
 * @param altMax            最高高度（不含），米
 * @param requestId         写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyReapplyRequest(
        @NotBlank @Size(max = 64) String occupancyId,
        @NotBlank @Size(max = 64) String parentOccupancyId,
        @NotBlank @Size(max = 64) String reviewId,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        @NotBlank @Size(max = 64) String requestId) {
}
