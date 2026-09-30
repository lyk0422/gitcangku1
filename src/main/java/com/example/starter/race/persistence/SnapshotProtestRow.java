package com.example.starter.race.persistence;

import com.example.starter.race.domain.ProtestStatus;

/**
 * result_snapshot_protest 表行记录：封榜时刻固化的申诉与裁决依据（只读）。
 *
 * @param protestId      申诉ID
 * @param raceId         所属赛事ID
 * @param bib            申诉选手参赛号
 * @param status         封榜时申诉终态：ACCEPTED / REJECTED
 * @param depositAmount  保证金快照金额（人民币元，正整数）
 * @param reason         申诉理由
 * @param decisionReason 裁决理由；未填写时为 null
 * @param createdAt      申诉创建时间，Unix毫秒时间戳
 * @param decidedAt      裁决时间，Unix毫秒时间戳
 * @param displayOrder   展示顺序，从0开始
 */
public record SnapshotProtestRow(
        String protestId,
        String raceId,
        String bib,
        ProtestStatus status,
        int depositAmount,
        String reason,
        String decisionReason,
        long createdAt,
        long decidedAt,
        int displayOrder
) {
}
