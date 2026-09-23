package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布方显式结算请求：提交快照完整预占版本集合。
 * 集合与快照不一致返回 409；仍有可合法确认项时保持 PENDING 并返回 409，不猜测结果。
 *
 * @param requestId 写操作全局唯一幂等键
 * @param items     快照完整预占版本集合（必须与撤回快照完全一致）
 */
public record SettleWithdrawalRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid List<ReservationVersionKey> items
) {
    /**
     * 预占版本键。
     *
     * @param reservationId   预占单编号
     * @param campaignVersion 冻结的公告版本
     */
    public record ReservationVersionKey(
            @NotBlank @Size(max = 64) String reservationId,
            @NotNull @Min(1) Integer campaignVersion
    ) {
    }
}
