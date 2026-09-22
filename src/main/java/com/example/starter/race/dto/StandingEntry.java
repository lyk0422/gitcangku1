package com.example.starter.race.dto;

/**
 * 单条成绩条目（即时成绩与封榜快照共用）。
 *
 * @param bib         参赛号
 * @param status      成绩状态（RANKED/UNTIMED/DISQUALIFIED）
 * @param totalTimeMs 总耗时（毫秒）=原始耗时+未撤销加时；非RANKED为null
 * @param rank        名次（并列同名次，下一名次跳过并列人数）；非RANKED为null
 */
public record StandingEntry(String bib, String status, Long totalTimeMs, Integer rank) {
}
