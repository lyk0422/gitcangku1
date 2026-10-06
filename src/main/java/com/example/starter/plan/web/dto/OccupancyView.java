package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开。
 * direction 为行车方向标识（无配置区段为 null）；
 * appliedConfigVersion 为发布成功时冻结的区段配置版本（草稿或无配置区段为 null），
 * 取消不改写，历史查询保持原值。
 */
public record OccupancyView(String trainNo, String sectionId, Instant startUtc, Instant endUtc,
                            String direction, Integer appliedConfigVersion) {
}
