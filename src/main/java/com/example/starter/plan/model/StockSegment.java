package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 车底交路段投影：一张已发布计划及其始发/终到时刻。
 *
 * @param startUtc         始发时刻，取计划全部占用的最早开始时刻
 * @param endUtc           终到时刻，取计划全部占用的最晚结束时刻
 * @param rearrangePending 待重排标记
 */
public record StockSegment(long planId, String scheduleKey, LocalDate opDate,
                           String originStation, String destinationStation,
                           Instant startUtc, Instant endUtc, boolean rearrangePending) {
}
