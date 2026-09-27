package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 检验台时段诊断视图：容量上限、窗口内进行中预约实际数与剩余差额，
 * 以及窗口内进行中预约明细（按开始时刻与创建顺序稳定排序）。
 *
 * @param benchKey       检验台业务键
 * @param capacity       同时段容量上限（要求值）
 * @param activeInWindow 窗口内进行中预约实际数（实际值）
 * @param remaining      剩余容量（差额 = capacity - activeInWindow）
 * @param reservations   窗口内进行中预约明细
 */
public record BenchScheduleView(
        String benchKey,
        Integer capacity,
        Integer activeInWindow,
        Integer remaining,
        List<ExamReservationView> reservations) {
}
