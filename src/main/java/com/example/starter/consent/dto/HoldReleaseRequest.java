package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保留冻结人工解除请求：解除人（请求头 X-Actor-Id）必须是不同于创建人的保留角色。
 *
 * @param requestId 幂等请求标识
 * @param note      解除说明
 */
public record HoldReleaseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String note) {
}
