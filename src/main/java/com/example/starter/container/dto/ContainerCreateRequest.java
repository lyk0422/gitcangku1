package com.example.starter.container.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建封存容器请求。
 *
 * @param commandKey       幂等命令键
 * @param containerId      容器业务键，全局唯一
 * @param nextInspectionAt 首次下次巡检截止时刻（UTC）
 */
public record ContainerCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String containerId,
        @NotNull LocalDateTime nextInspectionAt) {
}
