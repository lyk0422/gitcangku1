package com.example.starter.race.domain;

/**
 * 赛事计分模式：
 * STANDARD-标准计时排名（原始耗时+生效加时）；
 * HANDICAP-让分赛，有效成绩=原始毫秒×p/q+生效加时，按精确分数排名。
 */
public enum ScoringMode {
    STANDARD,
    HANDICAP
}
