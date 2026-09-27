package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量回执中的单个回执项。
 *
 * @param reservationId 预占单编号，必须归属批量请求指定的公告
 * @param receiptAtUtc  回执上报时刻，epoch 毫秒，UTC；不得早于预占时刻
 */
public record ReceiptItem(
        @NotBlank @Size(max = 64) String reservationId,
        @NotNull Long receiptAtUtc
) {
}
