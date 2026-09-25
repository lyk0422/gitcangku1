package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 运营方登记航线版本穿越序列请求。仅当 routeVersion 为当前版本时登记成功；
 * 同一版本重复登记为整体替换序列。只有当前版本的序列计入容量占用。
 *
 * @param routeId      航线标识
 * @param routeVersion 明确的航线版本（必须为当前版本）
 * @param items        有序穿越占用项，seq 从 0 连续递增，长度 2~50
 * @param requestId    写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyPlanRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<OccupancyPlanItemDto> items,
        @NotBlank @Size(max = 64) String requestId) {
}
