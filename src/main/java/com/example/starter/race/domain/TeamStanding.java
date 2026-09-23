package com.example.starter.race.domain;

import java.util.List;

/**
 * 单个团队的成绩条目。
 *
 * @param teamCode    团队代码，赛事内唯一
 * @param rank        团队名次，从1开始；完全同分并列同名次并跳号（1、1、3）；INCOMPLETE 为 null
 * @param status      团队成绩状态
 * @param totalTimeMs 团队总耗时=入选3人计分值合计（整数毫秒）；INCOMPLETE 为 null
 * @param members     全部成员的计分结果，按参赛号字典序排列
 */
public record TeamStanding(
        String teamCode,
        Integer rank,
        TeamStatus status,
        Long totalTimeMs,
        List<TeamMemberResult> members
) {
}
