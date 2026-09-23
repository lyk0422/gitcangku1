package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 带豁免核销的飞行审核请求。审核时刻以请求携带的 reviewAt（UTC）为准。
 *
 * @param flightKey 飞行审核唯一标识；同键只能形成一次审核，异参重放返回 409
 * @param routeId   被审核航线标识（使用其当前版本与当前空域的提交一致视图）
 * @param reviewAt  审核 UTC 时刻，epoch 毫秒，用于豁免有效区间判定
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record FlightReviewRequest(
        @NotBlank @Size(max = 64) String flightKey,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Long reviewAt,
        @NotBlank @Size(max = 64) String requestId) {
}
