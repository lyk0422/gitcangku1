package com.example.starter.race.persistence;

import com.example.starter.race.domain.AppealRecommendation;
import com.example.starter.race.domain.AppealSecondAction;
import com.example.starter.race.domain.AppealStatus;
import com.example.starter.race.domain.EntryStatus;

/**
 * penalty_appeal 表行记录：一次处罚申诉的受理冻结、两名干事意见与裁决重算结果。
 *
 * @param appealKey                 申诉键，全局唯一
 * @param raceId                    所属赛事ID
 * @param bib                       申诉选手参赛号
 * @param penaltyId                 被申诉处罚ID
 * @param status                    申诉状态
 * @param reason                    申诉理由
 * @param penaltyVersion            受理时冻结的处罚版本
 * @param timingVersion             受理时冻结的原始计时版本
 * @param segmentVersion            受理时冻结的分段判定版本
 * @param frozenLeaderboardVersion  受理时冻结的榜单（赛事）版本
 * @param frozenFinishTimeMs        冻结的原始完赛耗时（毫秒）；计时缺失为 null
 * @param frozenPenaltyMs           冻结的生效加时合计毫秒数
 * @param frozenTotalTimeMs         冻结的净成绩总耗时（毫秒）；未排名为 null
 * @param frozenRank                冻结的名次；未排名为 null
 * @param frozenEntryStatus         冻结的成绩状态
 * @param finishAtMs                选手 finishAt（最近计时修订时间）
 * @param beforeLeaderboard         受理时重算前完整榜单快照 JSON
 * @param afterLeaderboard          裁决重算后完整榜单快照 JSON；PENDING 时为 null
 * @param newPenaltyId              REPLACE 裁决生成的新处罚版本ID；否则为 null
 * @param firstStewardId            第一干事ID；未提交为 null
 * @param firstRecommendation       第一人建议；未提交为 null
 * @param firstReplacementMs        第一人 REPLACE 替代罚时（毫秒，允许0）；否则为 null
 * @param firstRecordedAt           第一人建议提交时间；未提交为 null
 * @param secondStewardId           第二干事ID；未裁决为 null
 * @param secondAction              第二人动作；未裁决为 null
 * @param secondRecordedAt          第二人裁决时间；未裁决为 null
 * @param createdAt                 受理时间
 * @param decidedAt                 裁决完成时间；PENDING 为 null
 */
public record PenaltyAppealRow(
        String appealKey,
        String raceId,
        String bib,
        String penaltyId,
        AppealStatus status,
        String reason,
        int penaltyVersion,
        int timingVersion,
        int segmentVersion,
        int frozenLeaderboardVersion,
        Long frozenFinishTimeMs,
        long frozenPenaltyMs,
        Long frozenTotalTimeMs,
        Integer frozenRank,
        EntryStatus frozenEntryStatus,
        long finishAtMs,
        String beforeLeaderboard,
        String afterLeaderboard,
        String newPenaltyId,
        String firstStewardId,
        AppealRecommendation firstRecommendation,
        Long firstReplacementMs,
        Long firstRecordedAt,
        String secondStewardId,
        AppealSecondAction secondAction,
        Long secondRecordedAt,
        long createdAt,
        Long decidedAt
) {
}
