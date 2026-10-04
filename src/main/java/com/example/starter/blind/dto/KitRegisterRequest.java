package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * SUPPLY 登记处理包请求体。处理代码仅用于落库，不会在响应中回显。
 *
 * @param packageNo 处理包编号，实验内唯一，非空，最长 64 字符
 * @param treatment 处理代码，仅允许 A 或 B
 */
public record KitRegisterRequest(
        @NotBlank(message = "packageNo 不能为空")
        @Size(max = 64, message = "packageNo 最长 64 字符")
        String packageNo,
        @NotBlank(message = "treatment 不能为空")
        @Pattern(regexp = "[AB]", message = "treatment 仅允许 A 或 B")
        String treatment
) {
}