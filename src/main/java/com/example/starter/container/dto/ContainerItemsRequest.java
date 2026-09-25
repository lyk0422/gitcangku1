package com.example.starter.container.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容器装载证物请求。evidenceKeys 为集合语义：重复元素去重，换序视为同参。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKeys 待装入容器的证物业务键集合，不能为空
 */
public record ContainerItemsRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
