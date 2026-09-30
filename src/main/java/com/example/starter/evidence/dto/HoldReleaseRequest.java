package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解除保留冻结请求。解除只影响后续销毁申请，不改写历史裁决。
 *
 * @param requestId 解除请求幂等键，全局唯一；同键换冻结或原因返回 409
 * @param reason    解除原因，非空，由不同于冻结创建人的保管人签明
 */
public record HoldReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 512) String reason) {
}
