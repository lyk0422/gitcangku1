package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容器移出请求：将指定证物移出容器；FAIL 容器禁止变更集合。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKeys 待移出证物业务键集合，非空
 */
public record ContainerUnloadRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
