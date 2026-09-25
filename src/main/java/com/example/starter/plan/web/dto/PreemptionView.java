package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 抢占记录视图：固化抢占双方计划、各自等级与涉及时隙，不可变。
 */
public record PreemptionView(long id, String preemptingScheduleKey, String preemptedScheduleKey,
                             int preemptingLevel, int preemptedLevel,
                             List<PreemptionSlotView> slots, Instant createdAt) {
}
