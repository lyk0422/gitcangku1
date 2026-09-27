package com.example.starter.race.domain;

/**
 * 医疗暂停区间视图：时间统一 UTC，区间左闭右开 [startAt, endAt)。
 * endAt 为 null 表示暂停生效中（右端点尚未确定）。
 */
public interface MedicalHoldView {

    /** 医疗暂停ID。 */
    String holdId();

    /** 选手参赛号。 */
    String bib();

    /** 暂停开始时刻，Unix毫秒时间戳（UTC），左闭端点。 */
    long startAt();

    /** 暂停结束时刻，Unix毫秒时间戳（UTC），右端点（不含）；生效中为 null。 */
    Long endAt();
}
