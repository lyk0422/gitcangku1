package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 晋级名单响应：生成、查询与撤销共用；内容来自不可变快照。
 *
 * @param advancementKey 晋级名单键
 * @param raceId         赛事ID
 * @param version        生成名单后的赛事版本号
 * @param quotaPerGroup  每组直接晋级名额 Q
 * @param wildcardCount  全局补位名额 W
 * @param expectedCount  计划晋级人数=Q乘组数加W
 * @param actualCount    实际晋级人数；并列跨过边界时大于计划人数
 * @param overflowReason 超额原因；无超额为 null
 * @param status         ACTIVE-生效中，REVOKED-已撤销
 * @param generatedAt    生成时间，Unix毫秒时间戳
 * @param revokedAt      撤销时间，Unix毫秒时间戳；未撤销为 null
 * @param entries        晋级条目（先 DIRECT 后 WILDCARD，按展示顺序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdvancementResponse(
        String advancementKey,
        String raceId,
        int version,
        int quotaPerGroup,
        int wildcardCount,
        int expectedCount,
        int actualCount,
        String overflowReason,
        String status,
        long generatedAt,
        Long revokedAt,
        List<AdvancementEntryResponse> entries
) {
}
