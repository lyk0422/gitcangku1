package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 区域高度带配置请求。以区域 expectedVersion 做乐观并发控制：
 * 只能上调已存在带容量，或新增不重叠带；不允许下调容量、删除带或修改带边界。
 *
 * @param zoneId          目标禁飞区标识
 * @param expectedVersion 客户端期望的区域配置版本；不匹配返回 409
 * @param bands           配置后该区域的完整高度带集合（含已有带与新增带）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record BandConfigureRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 1, max = 50) List<BandConfigDto> bands,
        @NotBlank @Size(max = 64) String requestId) {
}
