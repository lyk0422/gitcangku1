package com.example.starter.race.domain;

/**
 * 医疗暂停区间视图（不可变证据的只读投影）。
 * 时间为绝对 UTC 毫秒时间戳，区间左闭右开 [startAt, endAt)。
 */
public interface MedicalHoldView {

    /** 被暂停选手参赛号。 */
    String bib();

    /** 暂停开始的绝对 UTC 时刻（含）。 */
    long startAt();

    /** 暂停结束的绝对 UTC 时刻（不含）；null 表示暂停生效中。 */
    Long endAt();
}
