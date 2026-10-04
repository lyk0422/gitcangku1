package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * SUPPLY 登记处理包请求体。
 *
 * @param kitNo     包编号，同一实验内唯一，非空，最长 64 字符
 * @param treatment 处理代码，仅允许 A 或 B；仅可信 SUPPLY 可提交，绝不进入普通响应
 */
public record RegisterKitRequest(
        @NotBlank(message = "kitNo 不能为空")
        @Size(max = 64, message = "kitNo 长度不能超过 64 个字符")
        String kitNo,
        @NotBlank(message = "treatment 不能为空")
        @Pattern(regexp = "[AB]", message = "treatment 仅允许 A 或 B")
        String treatment
) {
}