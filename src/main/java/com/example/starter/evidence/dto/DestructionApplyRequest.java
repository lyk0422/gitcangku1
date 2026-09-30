package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 销毁申请请求。被有效冻结拒绝（409）也会落库；冻结到期或解除后须用新 requestId 重新申请。
 *
 * @param requestId 销毁申请幂等键，全局唯一，重放返回首次裁决
 * @param reason    销毁原因，非空
 */
public record DestructionApplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 512) String reason) {
}
