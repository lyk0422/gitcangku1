package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 显式完成接收请求。仅当已接收块恰好覆盖 [0,totalLength) 且拼接字节 SHA-256 与创建时摘要相等才成功。
 *
 * @param requestId       全局请求幂等键（非空，全局唯一）
 * @param expectedVersion 期望版本（当前已接收块数），可空；非空且不匹配返回 409
 */
public record ChunkCompleteRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(0) Integer expectedVersion) {
}
