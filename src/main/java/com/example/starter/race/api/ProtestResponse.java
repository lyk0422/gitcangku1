package com.example.starter.race.api;

import com.example.starter.race.domain.ProtestDecision;
import com.example.starter.race.domain.ProtestStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 申诉信息响应：固定金额为该申诉创建时冻结的保证金快照，不随赛事配置变化。
 *
 * @param protestId 申诉ID
 * @param raceId    所申诉赛事ID
 * @param accountId 提交申诉的账户ID
 * @param reason    申诉理由原文
 * @param depositFen 保证金快照金额，单位：分（1元=100分）
 * @param status    PENDING / ACCEPTED / REJECTED
 * @param decision  裁决结果 ACCEPT / REJECT；未裁决为 null
 * @param createdAt 申诉创建（冻结）时间，Unix毫秒时间戳
 * @param decidedAt 裁决时间，Unix毫秒时间戳；未裁决为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtestResponse(
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
