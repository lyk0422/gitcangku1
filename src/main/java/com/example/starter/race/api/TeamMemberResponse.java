package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 团队成员的计分信息响应。
 *
 * @param bib          参赛号
 * @param personalRank 个人名次；非 RANKED 为 null
 * @param status       个人成绩状态
 * @param totalTimeMs  含有效处罚的个人总耗时（毫秒）；非 RANKED 为 null
 * @param scored       是否入选团队计分前3人
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMemberResponse(
        String bib,
        Integer personalRank,
        EntryStatus status,
        Long totalTimeMs,
        boolean scored
) {
}
