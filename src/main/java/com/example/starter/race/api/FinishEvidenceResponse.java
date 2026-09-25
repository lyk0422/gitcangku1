package com.example.starter.race.api;

import com.example.starter.race.domain.EvidenceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 冲线证据响应。
 *
 * @param evidenceId     证据ID
 * @param raceId         所属赛事ID
 * @param finishTimeMs   候选组共享的原始完赛耗时（毫秒）
 * @param suggestedOrder 建议名次顺序
 * @param capturedAt     证据捕获UTC时刻，Unix毫秒时间戳
 * @param operator       登记操作者
 * @param finishKey      finishKey指纹
 * @param status         证据状态：PENDING / ADJUDICATED / WITHDRAWN
 * @param createdAt      登记时间，Unix毫秒时间戳
 * @param adjudicatedAt  裁决时间，Unix毫秒时间戳；未裁决为 null
 * @param withdrawnAt    撤回时间，Unix毫秒时间戳；未撤回为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinishEvidenceResponse(
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
