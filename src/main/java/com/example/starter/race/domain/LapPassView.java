package com.example.starter.race.domain;

/**
 * 选手圈次通过记录视图（绕圈赛事）。
 */
public interface LapPassView {

    /** 选手参赛号。 */
    String bib();

    /** 圈号，从1开始。 */
    int lapNo();

    /** 圈内点：A/B/FINISH。 */
    LapPoint point();

    /** 自起跑开始的累计耗时（毫秒，非负整数）。 */
    long elapsedMillis();
}