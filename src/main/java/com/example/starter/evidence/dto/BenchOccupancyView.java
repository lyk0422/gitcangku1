package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检验台占用诊断视图：给定时段（UTC 左闭右开）内的实际占用、容量要求与差额，
 * 以及重叠预约明细；只读，不改变任何状态。
 *
 * @param benchKey        检验台业务键
 * @param windowStart     诊断窗口开始时刻（UTC，左闭）
 * @param windowEnd       诊断窗口结束时刻（UTC，右开）
 * @param actualOccupancy 窗口内实际占位预约数（实际值）
 * @param capacity        台位容量（要求上限）
 * @param remaining       剩余容量差额（容量减实际占用；为负表示超容量）
 * @param overlapping     窗口内重叠占位预约明细（按开始时间稳定排序）
 */
public record BenchOccupancyView(
        String benchKey,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        int actualOccupancy,
        int capacity,
        int remaining,
        List<ExamReservationView> overlapping) {
}
