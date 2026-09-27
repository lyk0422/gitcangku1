package com.example.starter.race.domain;

/**
 * 终点（完赛）计时事件视图（不可变证据的只读投影）。
 * 用于恢复医疗暂停时判定暂停区间内是否已出现终点计时。
 */
public interface FinishTimingView {

    /** 选手参赛号。 */
    String bib();

    /** 终点计时事件发生的绝对 UTC 毫秒时间戳。 */
    long occurredAt();
}
