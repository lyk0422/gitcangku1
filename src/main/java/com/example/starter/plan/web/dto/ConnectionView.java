package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续关系视图。时长字段同时给出要求值、实际值与差额（正数为不足）；
 * actualMinutes 按当前计划时刻实时计算，取消后的接续仍按现存计划时刻计算。
 */
public record ConnectionView(
        long id,
        String predecessorKey,
        String successorKey,
        int guaranteeMinutes,
        String fromStation,
        String toStation,
        boolean crossesMidnight,
        String status,
        int actualMinutes,
        int requiredMinutes,
        int deficitMinutes,
        Instant createdAt) {
}