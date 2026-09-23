package com.example.starter.race.persistence;

import com.example.starter.race.domain.TeamStatus;

import java.util.List;

/**
 * team_snapshot_entry 表行记录（封榜固化的单个团队成绩）。
 *
 * @param raceId       所属团队快照的赛事ID
 * @param teamId       团队代码
 * @param rank         团队名次（竞赛排名1、1、3）；INCOMPLETE 为 null
 * @param status       COMPLETE / INCOMPLETE
 * @param totalTimeMs  入选3人个人总耗时整数毫秒合计；INCOMPLETE 为 null
 * @param displayOrder 展示顺序，从0开始
 * @param members      全部成员及其固化计分信息，按参赛号字典序
 */
public record TeamSnapshotEntryRow(
        String raceId,
        String teamId,
        Integer rank,
        TeamStatus status,
        Long totalTimeMs,
        int displayOrder,
        List<TeamSnapshotMemberRow> members
) {
}
