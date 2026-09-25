package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 双人复核封签请求。复核人须与 FAIL 巡检检查人不同，且同一容器两名复核人互不相同。
 *
 * @param commandKey 幂等命令键
 * @param note       复核说明，非空
 * @param reviewedAt 实际复核时刻（UTC）
 */
public record ContainerReviewRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String note,
        @NotNull LocalDateTime reviewedAt) {
}
