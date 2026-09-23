package com.example.starter.race.persistence;

import com.example.starter.race.domain.TeamStatus;

import java.util.List;

/**
 * result_snapshot_team 表行记录：封榜时固化的团队成绩，含成员计分依据。
 *
 * @param raceId       所属快照的赛事ID
 * @param teamCode     团队代码
 * @param rank         团队名次（并列同名次并跳号，如1、1、3）；INCOMPLETE 为 null
 * @param status       团队成绩状态：COMPLETE-已产生成绩，INCOMPLETE-RANKED 成员不足3人
 * @param totalTimeMs  团队总耗时=入选3人计分值合计（毫秒）；INCOMPLETE 为 null
 * @param displayOrder 展示顺序，从0开始：COMPLETE 按名次与 teamCode，INCOMPLETE 末尾按 teamCode
 * @param members      封榜时固化的全部成员计分行，按参赛号字典序排列
 */
public record SnapshotTeamRow(
        String raceId,
        String teamCode,
        Integer rank,
        TeamStatus status,
        Long totalTimeMs,
        int displayOrder,
        List<SnapshotTeamMemberRow> members
) {

    /** 不含成员明细的兼容构造器。 */
    public SnapshotTeamRow(
            String raceId,
            String teamCode,
            Integer rank,
            TeamStatus status,
            Long totalTimeMs,
            int displayOrder) {
        this(raceId, teamCode, rank, status, totalTimeMs, displayOrder, List.of());
    }
}
