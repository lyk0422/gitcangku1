package com.example.starter.race.persistence;

import com.example.starter.race.domain.AppealStatus;

/**
 * appeal 表行记录：申诉受理冻结、双人裁决意见与重算前后榜单快照。
 *
 * @param appealKey             申诉键，全局唯一
 * @param raceId                所属赛事ID
 * @param bib                   申诉选手参赛号
 * @param penaltyId             被申诉处罚ID
 * @param reason                申诉理由
 * @param status                申诉状态
 * @param penaltyVersion        受理时冻结的处罚版本号
 * @param frozenPenaltyType     受理时冻结的处罚类型
 * @param frozenPenaltyAmountMs 受理时冻结的加时毫秒数；取消资格为 null
 * @param frozenFinishTimeMs    受理时冻结的原始完赛耗时；计时缺失为 null
 * @param frozenPenaltyMs       受理时冻结的生效加时合计
 * @param frozenTotalTimeMs     受理时冻结的净成绩总耗时；未排名为 null
 * @param frozenRank            受理时冻结的名次；未排名为 null
 * @param frozenStatus          受理时冻结的成绩状态
 * @param frozenSegments        受理时冻结的分段判定JSON
 * @param leaderboardVersion    受理时冻结的榜单版本
 * @param firstOfficialId       第一人干事ID；无建议或被驳回后为 null
 * @param firstDecision         第一人建议
 * @param firstReplacementMs    第一人建议的替代罚时；仅 REPLACE 有值
 * @param firstAt               第一人提交时间
 * @param secondOfficialId      第二人干事ID
 * @param secondAction          第二人动作：CONFIRM / REJECT
 * @param secondDecision        第二人确认的建议
 * @param secondReplacementMs   第二人确认的替代罚时
 * @param secondAt              第二人操作时间
 * @param leaderboardBefore     重算前榜单快照JSON；未裁决为 null
 * @param leaderboardAfter      重算后榜单快照JSON；未裁决为 null
 * @param newLeaderboardVersion 裁决生成的新榜单版本；未裁决为 null
 * @param createdAt             受理时间，Unix毫秒时间戳
 * @param decidedAt             裁决完成时间；未裁决为 null
 */
public record AppealRow(
        String appealKey,
        String raceId,
        String bib,
        String penaltyId,
        String reason,
        AppealStatus status,
        int penaltyVersion,
        String frozenPenaltyType,
        Long frozenPenaltyAmountMs,
        Long frozenFinishTimeMs,
        long frozenPenaltyMs,
        Long frozenTotalTimeMs,
        Integer frozenRank,
        String frozenStatus,
        String frozenSegments,
        int leaderboardVersion,
        String firstOfficialId,
        String firstDecision,
        Long firstReplacementMs,
        Long firstAt,
        String secondOfficialId,
        String secondAction,
        String secondDecision,
        Long secondReplacementMs,
        Long secondAt,
        String leaderboardBefore,
        String leaderboardAfter,
        Integer newLeaderboardVersion,
        long createdAt,
        Long decidedAt
) {
}
