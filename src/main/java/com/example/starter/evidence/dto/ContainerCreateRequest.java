package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建容器请求。初始版本 0、状态 NORMAL。
 *
 * @param commandKey   幂等命令键
 * @param containerKey 容器业务键，全局唯一
 * @param location     存放位置描述
 */
public record ContainerCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String containerKey,
        @NotBlank @Size(max = 128) String location) {
}
