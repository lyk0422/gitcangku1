package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 与候选/申请窗口重叠的一条已生效站台占用（左闭右开，相邻不算重叠）。
 */
public record PlatformWindowConflictView(String scheduleKey, String trainNo,
                                         Instant startUtc, Instant endUtc) {
}
