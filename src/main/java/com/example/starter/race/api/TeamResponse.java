package com.example.starter.race.api;

import com.example.starter.race.domain.TeamStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个团队成绩响应。
 *
 * @param teamCode    团队代码
 * @param rank        团队名次（竞赛排名1、1、3）；INCOMPLETE 为 null
 * @param status      COMPLETE / INCOMPLETE
 * @param totalTimeMs 入选3人个人总耗时整数毫秒合计；INCOMPLETE 为 null
 * @param members     全部成员及其计分信息，按参赛号字典序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamResponse(
        String teamCode,
        Integer rank,
        TeamStatus status,
        Long totalTimeMs,
        List<TeamMemberResponse> members
) {
}
