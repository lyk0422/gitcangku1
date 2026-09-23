package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布方显式结算请求：提交快照完整预占版本集合。
 * 集合与快照不一致（缺项、多项或版本不符）返回 409。
 *
 * @param requestId 写操作全局唯一幂等键
 * @param items     快照完整预占版本集合（逐项：预占单编号 + 冻结版本）
 */
public record SettleWithdrawalRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Valid List<SettleItem> items
) {
    /**
     * 结算集合项：预占单编号与发布方认知的冻结版本。
     *
     * @param reservationId   预占单编号
     * @param campaignVersion 冻结的公告版本
     */
    public record SettleItem(
            @NotBlank @Size(max = 64) String reservationId,
            @NotNull Integer campaignVersion
    ) {
    }
}
