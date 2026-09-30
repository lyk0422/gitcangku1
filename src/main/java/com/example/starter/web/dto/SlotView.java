package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 仪器时间轴上的一个已分配区间。左闭右开。
 */
public record SlotView(
        String requestNo,
        String projectId,
        Instant startAt,
        Instant endAt,
        boolean fromPromotion,
        String promotedFromRequestNo) {
}
