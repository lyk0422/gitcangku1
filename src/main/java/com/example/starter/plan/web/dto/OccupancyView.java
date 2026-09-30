package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开。
 *
 * @param platformId 到发停靠站台 ID；无编组的纯区段占用为空
 */
public record OccupancyView(String trainNo, String sectionId, String platformId,
                            Instant startUtc, Instant endUtc) {
}
