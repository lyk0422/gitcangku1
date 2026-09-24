package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建草稿计划请求。requestKey 为幂等键；占用清单 1～30 条。
 * overnight 为 true 时声明夜间跨零点草稿（占用允许落在运营日 22:00 至次日 06:00），
 * 此时必须同时声明 nightPairKey；非夜间草稿可声明 nightPairKey 作为计划对的次日成员。
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        Boolean overnight,
        String nightPairKey,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies) {

    /**
     * 非夜间、无计划对的简式构造（兼容既有调用）。
     */
    public CreatePlanRequest(String requestKey, String scheduleKey, LocalDate opDate,
                             List<OccupancyRequest> occupancies) {
        this(requestKey, scheduleKey, opDate, null, null, occupancies);
    }
}
