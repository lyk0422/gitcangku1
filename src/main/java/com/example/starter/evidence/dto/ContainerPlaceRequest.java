package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物放入容器请求：仅当前保管人可操作，证物须 SEALED，容器须 ACTIVE。
 *
 * @param commandKey   幂等命令键
 * @param containerKey 目标容器业务键
 */
public record ContainerPlaceRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String containerKey) {
}
