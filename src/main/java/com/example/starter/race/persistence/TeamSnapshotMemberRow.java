package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.TeamStatus;

/**
 * team_snapshot_member 表行记录（封榜固化的团队成员计分信息）。
 *
 * @param raceId         所属团队快照的赛事ID
 * @param teamId         所属团队代码
 * @param bib            成员参赛号
 * @param personalRank   封榜时个人名次；非 RANKED 为 null
 * @param personalStatus 封榜时个人成绩状态
 * @param totalTimeMs    封榜时含有效处罚的个人总耗时（毫秒）；非 RANKED 为 null
 * @param scored         是否入选团队计分前3人
 * @param displayOrder   成员展示顺序，从0开始，按参赛号字典序
 */
public record TeamSnapshotMemberRow(
        String raceId,
        String teamId,
        String bib,
        Integer personalRank,
        EntryStatus personalStatus,
        Long totalTimeMs,
        boolean scored,
        int displayOrder
) {
}
