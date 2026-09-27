package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 站台占用诊断结果：当前规则（要求值）、实际峰值并行停靠数与容量差额，
 * 以及按占用起点稳定排序的生效站停明细。
 *
 * @param stationId        站点业务键
 * @param opDate           运营日期
 * @param platformCapacity 当前站点容量（要求值）
 * @param minDwellMinutes  当前最小停站分钟数
 * @param stationVersion   当前站点配置版本
 * @param peakConcurrent   实际峰值并行停靠数（实际值）
 * @param remaining        容量差额 = 容量 - 峰值，不为负
 * @param stops            生效站停明细
 */
public record StationDiagnosticsResponse(String stationId, LocalDate opDate, int platformCapacity,
                                         int minDwellMinutes, int stationVersion,
                                         int peakConcurrent, int remaining, List<Item> stops) {

    /**
     * 诊断中的一条生效站停。arrivalUtc/departureUtc 保留 null 语义（始发/终到）；
     * occupancyStart/occupancyEnd 为容量计算实际采用的左闭右开区间，
     * 单端站停按运营日边界补全。
     */
    public record Item(String scheduleKey, String trainNo, Instant arrivalUtc,
                       Instant departureUtc, Instant occupancyStart, Instant occupancyEnd) {
    }
}
