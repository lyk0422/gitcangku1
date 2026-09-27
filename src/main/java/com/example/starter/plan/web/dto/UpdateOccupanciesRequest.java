package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换草稿占用与站停登记请求，expectedVersion 必须与当前版本一致。
 * stationDwells 缺省视为空清单（替换语义：未传即清空登记）；operator 可空。
 */
public record UpdateOccupanciesRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies,
        @Size(max = 30) List<@Valid StationDwellRequest> stationDwells,
        String operator) {
}
