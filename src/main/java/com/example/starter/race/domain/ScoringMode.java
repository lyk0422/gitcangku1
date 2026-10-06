package com.example.starter.race.domain;

/**
 * 赛事计分模式：
 * STANDARD-按原始完赛耗时加罚时排名（默认）；
 * HANDICAP-让分模式，有效成绩=原始毫秒×p/q（精确分数）+未撤销罚时，
 * 排名比较精确分数，展示值 HALF_UP 舍入到整数毫秒。
 */
public enum ScoringMode {
    STANDARD,
    HANDICAP
}
