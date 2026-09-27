package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建草稿计划请求。requestKey 为幂等键；占用清单 1～30 条；
 * stationStops 为可选中间站到发登记（0～30 条，顺序即计划内序号）；
 * operatorId 为操作者标识（空白归一为 anonymous），参与幂等指纹。
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies,
        @Size(max = 30) List<@Valid StationStopRequest> stationStops,
        String operatorId) {
}
