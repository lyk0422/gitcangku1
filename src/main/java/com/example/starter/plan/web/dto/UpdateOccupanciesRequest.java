package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换草稿占用清单请求，expectedVersion 必须与当前版本一致；
 * stationStops 同时被整体替换，operator 参与幂等指纹。
 */
public record UpdateOccupanciesRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies,
        @NotNull @Size(max = 30) List<@Valid StationStopRequest> stationStops) {
}
