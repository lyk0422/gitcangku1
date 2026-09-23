package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 快照内预占的回执请求。预占单编号来自路径。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param receiptKey    回执键，全局唯一；同键同参重放原决议，异参 409
 * @param occurredAtUtc 客户端声明的曝光发生时刻，epoch 毫秒，UTC
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String receiptKey,
        @NotNull Long occurredAtUtc
) {
}
