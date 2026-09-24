package com.example.starter.race.persistence;

import java.util.List;

/**
 * advancement_snapshot 头表与其条目合成的晋级名单快照。
 *
 * @param advancementKey 晋级名单键，全局唯一；撤销后原快照保留，键不复用
 * @param raceId         所属赛事ID
 * @param version        生成名单后的赛事版本号
 * @param quotaPerGroup  每组直接晋级名额Q（1~8）
 * @param wildcardCount  全局补位名额W（0~8）
 * @param expectedCount  计划晋级人数=Q乘组数加W
 * @param actualCount    实际晋级人数；并列跨过边界时大于计划人数
 * @param overflowReason 超额原因；无超额为 null
 * @param status         名单状态：ACTIVE-生效中，REVOKED-已撤销（快照保留）
 * @param generatedAt    生成时间，Unix毫秒时间戳
 * @param revokedAt      撤销时间，Unix毫秒时间戳；未撤销为 null
 * @param entries        按展示顺序排列的晋级条目
 */
public record AdvancementRow(
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
        List<AdvancementEntryRow> entries
) {
}
