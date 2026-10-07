package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 放弃接收请求。仅创建人在 RECEIVING 状态可放弃；放弃后该接收为终态，只能另建新记录。
 *
 * @param requestId 全局请求幂等键（非空，全局唯一）
 * @param reason    放弃原因，可空（如摘要错误）
 */
public record ChunkAbandonRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 512) String reason) {
}
