package com.example.starter.race.domain;

/**
 * 选手圈次点位通过记录视图（当前记录，不含已更正历史）。
 */
public interface LapTimingView {

    /** 全局唯一圈次记录ID。 */
    String timingId();

    /** 选手参赛号。 */
    String bib();

    /** 圈号，从1到赛事总圈数。 */
    int lapNumber();

    /** 圈内点位：A/B/FINISH。 */
    LapPoint point();

    /** 自起跑开始的累计耗时（毫秒，非负整数）。 */
    long elapsedMillis();
}