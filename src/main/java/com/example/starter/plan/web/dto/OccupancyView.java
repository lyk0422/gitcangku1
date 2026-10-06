package com.example.starter.plan.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开。
 * speed 为占用速度（米每秒）；null 表示原无速度占用，不适用应急制动包络判定。
 */
public record OccupancyView(String trainNo, String sectionId, Instant startUtc, Instant endUtc,
                            BigDecimal speed) {
}
