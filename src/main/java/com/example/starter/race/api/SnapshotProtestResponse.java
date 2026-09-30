package com.example.starter.race.api;

import com.example.starter.race.domain.ProtestDecision;

/**
 * 封榜快照中固化的申诉与裁决依据（历史不可变）。
 *
 * @param protestId 申诉ID
 * @param accountId 提交申诉的账户ID
 * @param reason    申诉理由原文
 * @param depositFen 该申诉保证金快照固定金额，单位：分（1元=100分）
 * @param decision  封榜时的终态裁决：ACCEPT / REJECT
 * @param createdAt 申诉创建时间，Unix毫秒时间戳
 * @param decidedAt 裁决时间，Unix毫秒时间戳
 */
public record SnapshotProtestResponse(
        String protestId,
        String accountId,
        String reason,
        long depositFen,
        ProtestDecision decision,
        long createdAt,
        long decidedAt
) {
}
