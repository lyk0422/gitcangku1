package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建草稿计划请求。requestKey 为幂等键；占用清单 1～30 条。
 * stockKey/originStation/destStation 为车底交路登记，三者同时提供或同时缺省。
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        String stockKey,
        String originStation,
        String destStation,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies) {

    /**
     * 不登记车底交路的便捷构造。
     */
    public CreatePlanRequest(String requestKey, String scheduleKey, LocalDate opDate,
                             List<OccupancyRequest> occupancies) {
        this(requestKey, scheduleKey, opDate, null, null, null, occupancies);
    }
}
