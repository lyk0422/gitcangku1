package com.example.starter.plan;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建草稿计划请求。
 *
 * @param requestKey 幂等键
 * @param scheduleKey 计划业务键，全局唯一
 * @param operatingDate 运营日期（Asia/Shanghai 日历日）
 * @param occupancies 区段占用清单，1～30 条
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate operatingDate,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyInput> occupancies) {
}
