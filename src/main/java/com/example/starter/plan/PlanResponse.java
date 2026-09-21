package com.example.starter.plan;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应：计划头与完整占用清单。
 *
 * @param scheduleKey 计划业务键
 * @param operatingDate 运营日期（Asia/Shanghai 日历日）
 * @param version 当前版本
 * @param status 计划状态：DRAFT / PUBLISHED / CANCELLED
 * @param occupancies 占用清单（原始顺序）
 */
public record PlanResponse(
        String scheduleKey,
        LocalDate operatingDate,
        long version,
        PlanStatus status,
        List<OccupancyView> occupancies) {
}
