package com.example.starter.incident;

import java.time.Instant;

/**
 * 简报回读确认实体，对应 transfer_readbacks 表。
 * 回读绑定具体简报版本（briefing_id），刷新产生新版本后旧回读不能复用；
 * digest 为回读时提交且校验通过的摘要，(briefing_id, reader) 唯一。
 */
public record TransferReadback(
        long id,
        long briefingId,
        String reader,
        String digest,
        Instant createdAt) {
}