package com.example.starter.race.api;

import com.example.starter.race.domain.EvidenceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 冲线证据响应。
 *
 * @param evidenceId      证据ID
 * @param raceId          所属赛事ID
 * @param finishTimeMs    证据对应的相同计时（毫秒）
 * @param capturedAt      证据捕获UTC时刻，Unix毫秒时间戳
 * @param operator        登记操作者标识
 * @param status          PENDING / ADJUDICATED / REVOKED
 * @param suggestedOrder  建议顺序（参赛号列表）
 * @param rulingId        裁决批次ID；未裁决为 null
 * @param createdAt       登记时间，Unix毫秒时间戳
 * @param revokedAt       撤回时间，Unix毫秒时间戳；未撤回为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinishEvidenceResponse(
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
