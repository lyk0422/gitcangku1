package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 单个追加区组定义。
 *
 * @param treatments 4 个席位的处理代码，按席位提交顺序排列，必须恰好两个 A、两个 B；
 *                   顺序属于扩容请求参数，换序视为异参
 */
public record ExtensionBlockRequest(
        @NotNull(message = "treatments 不能为空")
        List<String> treatments
) {
}
