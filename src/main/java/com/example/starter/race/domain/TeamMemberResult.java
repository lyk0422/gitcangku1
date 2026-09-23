package com.example.starter.race.domain;

/**
 * 团队成员的计分结果。
 *
 * @param bib           参赛号
 * @param scoring       是否入选团队计分（COMPLETE 团队内 RANKED 选手按总耗时升序、
 *                      参赛号字典序取前3人）；INCOMPLETE 团队全部成员均为 false
 * @param scoringTimeMs 入选者的计分值（毫秒，即该选手含有效处罚的总耗时）；未入选为 null
 */
public record TeamMemberResult(
        String bib,
        boolean scoring,
        Long scoringTimeMs
) {
}
