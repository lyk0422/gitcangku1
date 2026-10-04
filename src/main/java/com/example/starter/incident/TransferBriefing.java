package com.example.starter.incident;

import java.time.Instant;

/**
 * 交接未结事项简报实体，对应 transfer_briefings 表。
 * 发起交接时创建 v1，交出人可在原待交接记录内刷新（版本加一，旧版本保留为 SUPERSEDED）；
 * 简报冻结事件版本、事件状态、未完成任务编号及状态、未确认升级记录与交出人补充说明，
 * 完整结构化内容序列化为 payloadJson，digest 为其 SHA-256（字段边界由 JSON 结构保证不可碰撞）。
 * readbackBy/readbackAt 仅接班人回读确认后有值；frozenAt 仅交接成功冻结后有值。
 * 时间均为 UTC。
 */
public record TransferBriefing(
        long id,
        long transferId,
        long incidentId,
        int version,
        long incidentVersion,
        String incidentStatus,
        String note,
        String payloadJson,
        String digest,
        BriefingStatus status,
        String readbackBy,
        Instant readbackAt,
        String createdBy,
        Instant createdAt,
        Instant frozenAt) {
}