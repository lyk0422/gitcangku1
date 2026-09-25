package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容器装载请求：将一批证物整体置入容器（覆盖式设置为该集合）。
 * 集合换序视为同参（服务层排序后参与指纹）；FAIL 容器禁止变更集合。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKeys 待装载证物业务键集合，非空，重复元素按一个处理
 */
public record ContainerLoadRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
