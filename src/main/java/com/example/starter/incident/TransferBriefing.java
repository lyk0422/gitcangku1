package com.example.starter.incident;

import java.time.Instant;

/**
 * 交接简报实体，对应 transfer_briefings 表。
 * 发起交接时创建 v1，交出人在原待交接记录内刷新时版本加一，旧版本保留。
 * content 为结构化简报 JSON（冻结事件状态、未完成任务编号及状态、未确认升级记录、
 * 交出人补充说明）；digest 为完整结构化内容的 SHA-256，接收时重算比对判断是否失效。
 * note 为 content 内补充说明的冗余列，便于刷新时沿用；status 在交接成功后置为 FROZEN。
 */
public record TransferBriefing(
        long id,
        long transferId,
        long incidentId,
        int version,
        String content,
        String digest,
        String note,
        BriefingStatus status,
        String createdBy,
        Instant createdAt) {
}