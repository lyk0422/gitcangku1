package com.example.starter.race.persistence;

import com.example.starter.race.domain.ProtestDecision;

/**
 * result_snapshot_protest 表行记录：封榜时固化的申诉与裁决依据（历史不可变）。
 *
 * @param raceId 所属封榜快照的赛事ID
 * @param protestId 申诉ID
 * @param accountId 提交申诉的账户ID
 * @param reason 申诉理由原文
 * @param depositFen 该申诉保证金快照金额，单位：分
 * @param decision 封榜时的终态裁决：ACCEPT / REJECT
 * @param createdAt 申诉创建时间，Unix毫秒时间戳
 * @param decidedAt 裁决时间，Unix毫秒时间戳
 */
public record SnapshotProtestRow(
        String raceId,
        String protestId,
        String accountId,
        String reason,
        long depositFen,
        ProtestDecision decision,
        long createdAt,
        long decidedAt
) {
}
