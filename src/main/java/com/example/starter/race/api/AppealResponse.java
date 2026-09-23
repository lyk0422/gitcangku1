package com.example.starter.race.api;

import com.example.starter.race.domain.AppealRecommendation;
import com.example.starter.race.domain.AppealSecondAction;
import com.example.starter.race.domain.AppealStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 处罚申诉证据响应（只读）：受理冻结、两名干事意见与裁决重算前后榜单快照全部保留。
 *
 * @param appealKey        申诉键
 * @param raceId           所属赛事ID
 * @param bib              申诉选手参赛号
 * @param penaltyId        被申诉处罚ID
 * @param status           PENDING / UPHELD / REMOVED / REPLACED / REJECTED
 * @param reason           申诉理由
 * @param timingVersion    受理时冻结的原始计时版本
 * @param segmentVersion   受理时冻结的分段判定版本
 * @param finishAtMs       选手 finishAt（最近计时修订时间），Unix毫秒时间戳
 * @param frozenResult     受理时冻结的原始/净成绩与榜单版本
 * @param frozenSegments   受理时冻结的分段判定，按检查点顺序稳定排列
 * @param firstStewardId   第一干事ID；未提交为 null
 * @param firstRecommendation 第一人建议；未提交为 null
 * @param firstReplacementMs  第一人 REPLACE 替代罚时（毫秒，允许0）；否则为 null
 * @param firstRecordedAt  第一人建议提交时间；未提交为 null
 * @param secondStewardId  第二干事ID；未裁决为 null
 * @param secondAction     第二人动作 CONFIRM/REJECT；未裁决为 null
 * @param secondRecordedAt 第二人裁决时间；未裁决为 null
 * @param newPenaltyId     REPLACE 裁决生成的新处罚版本ID；否则为 null
 * @param beforeLeaderboard 受理时重算前完整榜单快照
 * @param afterLeaderboard 裁决重算后完整榜单快照；仍 PENDING 为 null
 * @param createdAt        受理时间，Unix毫秒时间戳
 * @param decidedAt        裁决完成时间，Unix毫秒时间戳；PENDING 为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppealResponse(
        String appealKey,
        String raceId,
        String bib,
        String penaltyId,
        AppealStatus status,
        String reason,
        int timingVersion,
        int segmentVersion,
        long finishAtMs,
        FrozenResultResponse frozenResult,
        List<AppealSegmentResponse> frozenSegments,
        String firstStewardId,
        AppealRecommendation firstRecommendation,
        Long firstReplacementMs,
        Long firstRecordedAt,
        String secondStewardId,
        AppealSecondAction secondAction,
        Long secondRecordedAt,
        String newPenaltyId,
        StandingResponse beforeLeaderboard,
        StandingResponse afterLeaderboard,
        long createdAt,
        Long decidedAt
) {
}
