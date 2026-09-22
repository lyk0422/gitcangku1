package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建实验请求：固定 2~8 个区组，每组 4 个席位，按提交顺序携带两个 A 和两个 B 处理代码。
 */
public record CreateExperimentRequest(
        @NotBlank String requestId,
        @NotBlank String experimentId,
        @NotNull @Size(min = 2, max = 8) List<@Size(min = 4, max = 4) List<@NotBlank String>> blocks) {
}
