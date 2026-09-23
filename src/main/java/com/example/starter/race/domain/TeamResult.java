package com.example.starter.race.domain;

import java.util.List;

/**
 * 单个团队的成绩。
 *
 * @param teamCode  团队代码
 * @param rank      团队名次（竞赛排名 1、1、3）；INCOMPLETE 为 null
 * @param status    COMPLETE / INCOMPLETE
 * @param totalTimeMs 入选3人个人总耗时的整数毫秒合计；INCOMPLETE 为 null
 * @param members   全部成员及其计分信息，按参赛号字典序稳定排列
 */
public record TeamResult(
        String teamCode,
        Integer rank,
        TeamStatus status,
        Long totalTimeMs,
        List<TeamMemberScore> members
) {
}
