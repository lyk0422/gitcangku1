package com.example.starter.race.persistence;

import com.example.starter.race.domain.EvidenceStatus;

import java.util.List;

/**
 * finish_evidence 表行记录（冲线证据）。
 *
 * @param evidenceId        证据ID，全局唯一不可重复
 * @param raceId            所属赛事ID
 * @param finishTimeMs      证据对应的相同计时（毫秒）
 * @param capturedAt        证据捕获UTC时刻，Unix毫秒时间戳
 * @param operator          登记操作者标识
 * @param status            证据状态：PENDING / ADJUDICATED / REVOKED
 * @param suggestedOrder    建议顺序（参赛号列表）；候选不得遗漏或重复
 * @param rulingId          裁决批次ID；未裁决为 null
 * @param createdAt         登记时间，Unix毫秒时间戳
 * @param revokedAt         撤回时间，Unix毫秒时间戳；未撤回为 null
 */
public record FinishEvidenceRow(
        String evidenceId,
        String raceId,
        long finishTimeMs,
        long capturedAt,
        String operator,
        EvidenceStatus status,
        List<String> suggestedOrder,
        String rulingId,
        long createdAt,
        Long revokedAt
) {
}
