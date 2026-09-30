package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 独立确认人核对启用申请请求体；确认的版本必须与申请版本一致。
 *
 * @param configVersion 复核确认的配置版本号，与申请不一致返回 409 且不改变状态
 */
public record ConfirmActivationRequest(
        @NotNull(message = "configVersion 不能为空")
        @Min(value = 1, message = "configVersion 必须为正整数")
        Integer configVersion
) {
}
