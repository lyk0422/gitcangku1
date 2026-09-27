package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销材料批次请求。撤销不可逆；已被在途证据引用时不影响已固化快照，但新提交将被拒绝。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param reason     撤销原因（可空，null 表示不填写）
 */
public record RevokeMaterialRequest(
        @NotBlank String requestId,
        @Size(max = 256) String reason) {
}
