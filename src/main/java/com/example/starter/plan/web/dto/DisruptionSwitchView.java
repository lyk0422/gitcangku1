package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 封锁切换单视图，窗口为左闭右开 UTC 区间。
 */
public record DisruptionSwitchView(String switchKey, String sectionId, Instant windowStartUtc,
                                   Instant windowEndUtc, String status) {
}
