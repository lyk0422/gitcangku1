package com.example.starter.race.domain;

/**
 * 选手退赛记录视图（不可变证据的只读投影）。
 */
public interface WithdrawalView {

    /** 退赛选手参赛号。 */
    String bib();

    /** 退赛提交的绝对 UTC 毫秒时间戳，作为排序与裁决依据。 */
    long withdrawnAt();
}
