package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改区域高度带配置请求。只允许上调已有高度带容量或新增不重叠高度带；
 * 携带区域高度带配置 expectedVersion，冲突返回 409；不追溯改写已有占用记录。
 * newBands 与 capacityUpdates 至少一项非空。
 *
 * @param zoneId          禁飞区唯一标识
 * @param expectedVersion 期望的当前高度带配置版本（从 1 开始）
 * @param newBands        新增高度带（可为空）
 * @param capacityUpdates 容量上调项（可为空）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ZoneBandsModifyRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull Integer expectedVersion,
        @Valid List<AltitudeBandDto> newBands,
        @Valid List<BandCapacityUpdateDto> capacityUpdates,
        @NotBlank @Size(max = 64) String requestId) {
}
