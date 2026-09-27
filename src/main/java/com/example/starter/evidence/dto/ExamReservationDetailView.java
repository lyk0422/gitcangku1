package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 检验预约明细视图：预约当前状态 + 该预约的完整事件链（按发生顺序）。
 *
 * @param reservation 预约视图
 * @param events      该预约的检验事件链，只追加
 */
public record ExamReservationDetailView(
        ExamReservationView reservation,
        List<ExamEventView> events) {
}
