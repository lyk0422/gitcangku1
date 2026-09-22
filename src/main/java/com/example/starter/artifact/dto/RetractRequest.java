package com.example.starter.artifact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 制品撤回请求。撤回仅标记不删除，仓库版本加一。
 *
 * @param requestId 全局唯一幂等请求 id
 */
public record RetractRequest(@NotBlank @Size(max = 64) String requestId) {
}
