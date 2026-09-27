package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 处理代码退役请求体。
 *
 * @param codes  待退役的处理代码集合；至少一个，元素为单个字母（大小写不敏感，
 *               服务端规范化为去重排序后的大写集合再裁决）
 * @param reason 退役理由，必填，写入后不可变
 */
public record RetireCodesRequest(
        @NotEmpty(message = "codes 不能为空")
        List<@NotBlank(message = "处理代码不能为空") String> codes,
        @NotBlank(message = "reason 不能为空")
        @Size(max = 500, message = "reason 最长 500 字符")
        String reason
) {
}
