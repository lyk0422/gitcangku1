package com.example.starter.model;

import com.example.starter.domain.RequestStatus;

import java.time.Instant;

/**
 * 观测窗口申请 PO。时间均为 UTC，窗口为左闭右开区间 {@code [startAt, endAt)}。
 *
 * @param id 主键
 * @param requestNo 申请编号，候补同申请时间时按编号升序
 * @param projectId 项目（研究组）标识
 * @param instrumentId 仪器标识
 * @param startAt 窗口开始时刻（UTC，包含）
 * @param endAt 窗口结束时刻（UTC，不包含）
 * @param durationMinutes 所需观测分钟数
 * @param status 当前状态
 * @param requestedAt 申请时间（UTC，候补排序第一关键字）
 * @param promotedFromRequestId 接替来源申请 ID；仅 PROMOTED 非空
 * @param promotedAt 接替时刻（UTC）；仅 PROMOTED 非空
 */
public record ObservationRequest(
        Long id,
        String requestNo,
        String projectId,
        String instrumentId,
        Instant startAt,
        Instant endAt,
        int durationMinutes,
        RequestStatus status,
        Instant requestedAt,
        Long promotedFromRequestId,
        Instant promotedAt) {
}
