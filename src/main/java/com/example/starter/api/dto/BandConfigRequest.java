package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 区域高度带配置修改请求。只允许：上调既有高度带容量、新增与既有带不重叠的带；
 * 不允许下调容量、删除带或修改带边界。携带区域 expectedVersion 做乐观锁，
 * 冲突返回 409。修改不追溯改写已有占用记录，但会推进空域版本使旧审查变为 STALE。
 *
 * @param zoneId          区域唯一标识
 * @param expectedVersion 期望的区域高度带配置版本
 * @param bands           修改后的完整高度带配置（既有带不得缺失，边界不得改变）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record BandConfigRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 1, max = 50) List<BandSpecDto> bands,
        @NotBlank @Size(max = 64) String requestId) {
}
