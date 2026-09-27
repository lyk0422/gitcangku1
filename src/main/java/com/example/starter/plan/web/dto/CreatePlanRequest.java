package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建草稿计划请求。requestKey 为幂等键；占用清单 1～30 条。
 * hazardCars 为按列车登记的危险品编组（可空表示无危险品车厢），其 trainNo 必须出现在 occupancies 中。
 * operator 为操作者标识，缺省 system，参与幂等指纹；不允许空白串。
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        String operator,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies,
        @Size(max = 100) List<@Valid HazardCarRequest> hazardCars) {

    /** 无操作者、无危险品编组的便捷构造器（操作者缺省 system）。 */
    public CreatePlanRequest(String requestKey, String scheduleKey, LocalDate opDate,
                             List<OccupancyRequest> occupancies) {
        this(requestKey, scheduleKey, opDate, null, occupancies, null);
    }
}
