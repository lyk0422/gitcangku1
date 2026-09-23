package com.example.starter.race.api;

import com.example.starter.race.domain.TeamStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个团队的成绩条目视图。
 *
 * @param teamCode    团队代码
 * @param rank        团队名次（并列同名次并跳号，如1、1、3）；INCOMPLETE 为 null
 * @param status      COMPLETE / INCOMPLETE
 * @param totalTimeMs 团队总耗时=入选3人计分值合计（毫秒）；INCOMPLETE 为 null
 * @param members     全部成员及其计分结果，按参赛号字典序排列
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamStandingEntryResponse(
        String teamCode,
        Integer rank,
        TeamStatus status,
        Long totalTimeMs,
        List<TeamMemberResultResponse> members
) {
}
