package com.example.starter.race.api;

import java.util.List;

/**
 * 团队成绩条目。
 *
 * @param teamId        团队ID
 * @param rosterVersion 计分所引用的完整名单版本号（当前最新锁定版本，或封榜时固化的版本）
 * @param totalTimeMs   团队总耗时（毫秒）=版本内全部参与计分成员总耗时之和
 * @param memberCount   名单版本固化成员数
 * @param scoringCount  实际参与团队计分的成员数（封榜前按退赛生效场次排除；封榜快照为固化值）
 * @param members       成员个人成绩（按参赛号字典序），字段含义同 {@link ResultEntryResponse}
 */
public record TeamScoreResponse(
        String teamId,
        int rosterVersion,
        long totalTimeMs,
        int memberCount,
        int scoringCount,
        List<TeamMemberScoreResponse> members
) {
}
