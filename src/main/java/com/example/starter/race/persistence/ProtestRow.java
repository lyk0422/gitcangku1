package com.example.starter.race.persistence;

import com.example.starter.race.domain.ProtestDecision;
import com.example.starter.race.domain.ProtestStatus;

/**
 * protest 表行记录。
 *
 * @param protestId 申诉ID，全局唯一
 * @param raceId 所申诉赛事ID
 * @param accountId 提交申诉并被冻结保证金的账户ID
 * @param reason 申诉理由原文
 * @param depositFen 保证金快照金额，单位：分；取创建时赛事当前配置，此后不随配置变化
 * @param status 申诉状态：PENDING / ACCEPTED / REJECTED
 * @param decision 裁决结果：ACCEPT / REJECT；未裁决为 null
 * @param createdAt 申诉创建（保证金冻结）时间，Unix毫秒时间戳
 * @param decidedAt 裁决时间，Unix毫秒时间戳；未裁决为 null
 */
public record ProtestRow(
        String protestId,
        String raceId,
        String accountId,
        String reason,
        long depositFen,
        ProtestStatus status,
        ProtestDecision decision,
        long createdAt,
        Long decidedAt
) {
}
