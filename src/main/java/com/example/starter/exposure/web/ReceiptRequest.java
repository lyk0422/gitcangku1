package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 曝光回执请求。仅受理撤回快照内的预占；
 * occurredAt 早于截点、不早于预占时刻且提交时未过到期时刻才可确认。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param receiptKey    回执键，全局唯一；同键同参重放原决议，异参 409
 * @param reservationId 快照内预占单编号
 * @param occurredAt    曝光发生时刻（epoch 毫秒，UTC）
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String receiptKey,
        @NotBlank @Size(max = 64) String reservationId,
        @NotNull Long occurredAt
) {
}
