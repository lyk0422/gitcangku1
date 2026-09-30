package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 中心负责人提交启用申请请求体。
 *
 * @param configVersion 申请启用的配置版本号，必须为已提交版本
 */
public record ApplyActivationRequest(
        @NotNull(message = "configVersion 不能为空")
        @Min(value = 1, message = "configVersion 必须为正整数")
        Integer configVersion
) {
}
