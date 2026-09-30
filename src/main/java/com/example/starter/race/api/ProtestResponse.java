package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 申诉响应：申诉状态与创建时冻结的保证金快照金额。
 *
 * @param protestId      申诉ID
 * @param raceId         赛事ID
 * @param bib            申诉选手参赛号
 * @param status         申诉状态：PENDING / ACCEPTED / REJECTED
 * @param depositAmount  保证金快照金额（人民币元，正整数），不受配置后续变化影响
 * @param reason         申诉理由
 * @param decisionReason 裁决理由；PENDING 状态为 null
 * @param createdAt      创建时间（冻结时间），Unix毫秒时间戳
 * @param decidedAt      裁决时间（退回/没收时间），Unix毫秒时间戳；PENDING 状态为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtestResponse(
        String protestId,
        String raceId,
        String bib,
        String status,
        int depositAmount,
        String reason,
        String decisionReason,
        long createdAt,
        Long decidedAt
) {
}
