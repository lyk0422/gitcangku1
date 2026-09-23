package com.example.starter.race.api;

import com.example.starter.race.domain.AppealStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 申诉证据响应：受理冻结、双人裁决意见与重算前后榜单快照，只读。
 *
 * @param appealKey             申诉键
 * @param raceId                所属赛事ID
 * @param bib                   申诉选手参赛号
 * @param penaltyId             被申诉处罚ID
 * @param status                申诉状态
 * @param reason                申诉理由
 * @param penaltyVersion        受理时冻结的处罚版本号
 * @param freeze                受理时冻结的处罚、成绩、分段判定与榜单版本
 * @param firstOpinion          第一人建议；尚无或被驳回后为 null
 * @param secondOpinion         第二人动作；未操作为 null
 * @param leaderboardBefore     重算前完整榜单快照；未裁决为 null
 * @param leaderboardAfter      重算后完整榜单快照；未裁决为 null
 * @param newLeaderboardVersion 裁决生成的新榜单版本；未裁决为 null
 * @param createdAt             受理时间，Unix毫秒时间戳
 * @param decidedAt             裁决完成时间；未裁决为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppealResponse(
        String appealKey,
        String raceId,
        String bib,
        String penaltyId,
        AppealStatus status,
        String reason,
        int penaltyVersion,
        Freeze freeze,
        Opinion firstOpinion,
        Opinion secondOpinion,
        List<ResultEntryResponse> leaderboardBefore,
        List<ResultEntryResponse> leaderboardAfter,
        Integer newLeaderboardVersion,
        long createdAt,
        Long decidedAt
) {

    /**
     * 受理冻结内容。
     *
     * @param penaltyType      冻结的处罚类型
     * @param penaltyAmountMs  冻结的加时毫秒数；取消资格为 null
     * @param finishTimeMs     冻结的原始完赛耗时；计时缺失为 null
     * @param penaltyMs        冻结的生效加时合计
     * @param totalTimeMs      冻结的净成绩总耗时；未排名为 null
     * @param rank             冻结的名次；未排名为 null
     * @param status           冻结的成绩状态
     * @param segments         冻结的分段判定，按检查点顺序
     * @param leaderboardVersion 冻结的榜单版本
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Freeze(
            String penaltyType,
            Long penaltyAmountMs,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs,
            Integer rank,
            String status,
            List<Segment> segments,
            int leaderboardVersion
    ) {
    }

    /**
     * 冻结的单个检查点分段判定。
     *
     * @param checkpointCode 检查点代码
     * @param position       检查点顺序
     * @param elapsedMillis  通过累计耗时毫秒；缺失为 null
     * @param timingId       分段记录ID；缺失为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Segment(
            String checkpointCode,
            int position,
            Long elapsedMillis,
            String timingId
    ) {
    }

    /**
     * 一名赛事干事的意见。
     *
     * @param officialId    干事ID
     * @param action        动作：第一人固定为 RECOMMEND；第二人为 CONFIRM / REJECT
     * @param decision      建议：UPHOLD / REMOVE / REPLACE；REJECT 无值为 null
     * @param replacementMs 替代罚时毫秒；仅 REPLACE 有值
     * @param at            提交时间，Unix毫秒时间戳
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Opinion(
            String officialId,
            String action,
            String decision,
            Long replacementMs,
            long at
    ) {
    }
}
