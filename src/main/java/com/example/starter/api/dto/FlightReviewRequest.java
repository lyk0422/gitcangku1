package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 带豁免核销的航线审核请求。以 flightKey 标识一次业务航班审核，
 * reviewAt 为 UTC 审核时刻（决定豁免项是否在有效区间内）。
 *
 * @param flightKey 航班业务标识，全局只能形成一次成功审核
 * @param routeId   被审核航线标识
 * @param reviewAt  审核时刻，epoch 毫秒（UTC）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record FlightReviewRequest(
        @NotBlank @Size(max = 64) String flightKey,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Long reviewAt,
        @NotBlank @Size(max = 64) String requestId) {
}
