package com.example.starter.race.api;

/**
 * 接力队伍登记的单个棒次选手。
 *
 * @param legNo 棒次序号（从1开始）
 * @param bib   该棒次选手参赛号
 */
public record RelayMemberResponse(int legNo, String bib) {
}
