package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 容器复核放行请求。放行后容器回到 NORMAL，箱内隔离证物回到 SEALED；
 * 巡检、隔离历史与带时刻的复核结论保留。
 *
 * @param commandKey 幂等命令键
 * @param note       复核结论说明，可空
 */
public record ContainerReviewRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Size(max = 512) String note) {
}
