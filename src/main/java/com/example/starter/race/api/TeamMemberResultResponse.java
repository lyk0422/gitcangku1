package com.example.starter.race.api;

/**
 * 团队成员的计分结果视图。
 *
 * @param bib           参赛号
 * @param scoring       是否入选团队计分（前3名 RANKED 成员）
 * @param scoringTimeMs 入选者的计分值（毫秒）；未入选为 null
 */
public record TeamMemberResultResponse(
        String bib,
        boolean scoring,
        Long scoringTimeMs
) {
}
