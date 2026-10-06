package com.example.starter.plan.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开。
 * speedV 仅在有速度占用时输出；无速度占用保持原接口（不序列化该字段）。
 */
public record OccupancyView(String trainNo, String sectionId, Instant startUtc, Instant endUtc,
                            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal speedV) {
}
