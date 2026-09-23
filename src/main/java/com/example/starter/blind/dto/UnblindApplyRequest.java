package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 揭盲申请请求体。
 *
 * @param reason       揭盲原因，必填
 * @param validMinutes 申请有效时长（分钟），可选，缺省 30，取值 1~60
 */
public record UnblindApplyRequest(
        @NotBlank(message = "reason 不能为空")
        @Size(max = 500, message = "reason 最长 500 字符")
        String reason,

        @Min(value = 1, message = "validMinutes 必须在 1~60 之间")
        @Max(value = 60, message = "validMinutes 必须在 1~60 之间")
        Integer validMinutes
) {
}
