package com.example.starter.race.domain;

/**
 * 选手视图：参赛号、原始完赛耗时与退赛标记。
 */
public interface RunnerView {

    String bib();

    /** 原始完赛耗时（毫秒）；null 表示计时缺失。 */
    Long finishTimeMs();

    /** 是否已退赛；默认在赛，兼容不关心退赛的调用方与测试夹具。 */
    default boolean withdrawn() {
        return false;
    }
}
