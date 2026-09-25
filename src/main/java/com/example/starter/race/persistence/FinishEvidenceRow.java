package com.example.starter.race.persistence;

import com.example.starter.race.domain.EvidenceStatus;

import java.util.List;

/**
 * finish_evidence 表行记录。
 *
 * @param evidenceId     证据ID，全局唯一
 * @param raceId         所属赛事ID
 * @param finishTimeMs   候选组共享的原始完赛耗时（毫秒）
 * @param suggestedOrder 建议名次顺序（候选参赛号全排列）
 * @param capturedAt     证据捕获UTC时刻，Unix毫秒时间戳
 * @param operator       登记操作者
 * @param finishKey      finishKey指纹（赛事版本+证据+规范化候选顺序+操作者）
 * @param status         证据状态
 * @param createdAt      登记时间，Unix毫秒时间戳
 * @param adjudicatedAt  裁决时间，Unix毫秒时间戳；未裁决为 null
 * @param withdrawnAt    撤回时间，Unix毫秒时间戳；未撤回为 null
 */
public record FinishEvidenceRow(
        String evidenceId,
        String raceId,
        long finishTimeMs,
        List<String> suggestedOrder,
        long capturedAt,
        String operator,
        String finishKey,
        EvidenceStatus status,
        long createdAt,
        Long adjudicatedAt,
        Long withdrawnAt
) {
}
