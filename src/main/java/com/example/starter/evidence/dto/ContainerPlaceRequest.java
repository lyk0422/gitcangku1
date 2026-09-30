package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物放入/移出容器请求。仅当前保管人可操作；证物须为 SEALED。
 * 放入会使源容器与目标容器版本自增。
 *
 * @param commandKey  幂等命令键
 * @param evidenceKey 证物业务键
 */
public record ContainerPlaceRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey) {
}
