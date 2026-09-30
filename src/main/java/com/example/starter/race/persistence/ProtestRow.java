package com.example.starter.race.persistence;

import com.example.starter.race.domain.ProtestStatus;

/**
 * protest 表行记录：一次成绩申诉及其保证金快照。
 *
 * @param protestId     申诉ID，全局唯一
 * @param raceId        所属赛事ID
 * @param bib           申诉选手参赛号
 * @param status        申诉状态：PENDING / ACCEPTED / REJECTED
 * @param depositAmount 保证金快照金额（人民币元，正整数）：创建时取赛事当前配置，后续配置变更不影响
 * @param reason        申诉理由
 * @param decisionReason 裁决理由；PENDING 时为 null
 * @param createdAt     创建时间（冻结时间），Unix毫秒时间戳
 * @param decidedAt     裁决时间（退回/没收时间），Unix毫秒时间戳；PENDING 时为 null
 */
public record ProtestRow(
        String protestId,
        String raceId,
        String bib,
        ProtestStatus status,
        int depositAmount,
        String reason,
        String decisionReason,
        long createdAt,
        Long decidedAt
) {
}
