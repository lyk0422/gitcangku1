package com.example.starter.handoff.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * 移交证据只读视图：移交单概要 + 冻结清单（含封签与发运前快照）+
 * 创建/接收两阶段血缘闭包快照 + 不可变事件证据。全部列表稳定排序。
 */
public record HandoffEvidenceResponse(
        String manifestKey,
        String sourcePlant,
        String targetPlant,
        String status,
        Instant expectedArrivalAt,
        Instant createdAt,
        Instant shippedAt,
        Instant receivedAt,
        String receiver,
        List<ItemEvidence> items,
        List<LineageEvidence> lineage,
        List<EventEvidence> events
) {

    /**
     * 冻结清单项：seq 为创建请求顺序；sealNo 发运后写入；
     * statusBeforeShip/versionBeforeShip 为发运前批次快照，供取消原子恢复。
     */
    public record ItemEvidence(
            String batchKey,
            long expectedVersion,
            int seq,
            String sealNo,
            String statusBeforeShip,
            Long versionBeforeShip
    ) {
    }

    /**
     * 血缘闭包快照项：CREATE 为创建冻结基线，RECEIVE 为接收时服务端重新展开的完整闭包。
     */
    public record LineageEvidence(
            String batchKey,
            String ancestorKey,
            int depth,
            String ancestorStatus,
            String ancestorHolderPlant,
            String phase
    ) {
    }

    /**
     * 不可变事件证据：HANDOFF_SHIPPED 源厂交接快照、HANDOFF_RECEIVED 接收快照、
     * HANDOFF_CANCELLED 取消快照；payload 为事件落定时刻的完整 JSON。
     */
    public record EventEvidence(
            String eventType,
            JsonNode payload,
            Instant createdAt
    ) {
    }
}
