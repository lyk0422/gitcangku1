package com.example.starter.plan.web.dto;

import com.example.starter.plan.model.Direction;
import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开。
 * direction 为发布时冻结的行车方向（未指定为 null）；
 * clearanceVersion 为发布成功时冻结的单线区段配置版本（未配置或未发布为 null）。
 */
public record OccupancyView(String trainNo, String sectionId, Instant startUtc, Instant endUtc,
                            Direction direction, Integer clearanceVersion) {
}
