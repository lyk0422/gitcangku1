package com.example.starter.container.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 容器复核封签请求。仅 INSPECTION_FAILED 容器可复核；同一保管人至多一次，
 * 两名不同保管人完成后容器恢复 SEALED。
 *
 * @param commandKey 幂等命令键
 * @param note       复核说明，可空
 */
public record ContainerReviewRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Size(max = 512) String note) {
}
