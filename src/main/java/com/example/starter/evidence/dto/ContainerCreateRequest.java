package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 容器登记请求。保管人取自 X-Actor-Id，初始封条版本为 1，状态 ACTIVE。
 *
 * @param commandKey   幂等命令键
 * @param containerKey 容器业务键，全局唯一
 * @param location     容器存放位置描述（库房/货架编号）
 */
public record ContainerCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String containerKey,
        @NotBlank @Size(max = 128) String location) {
}
