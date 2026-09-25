package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 双人激活/恢复确认请求体；两名不同的未盲管理人员必须提交相同 activationKey。
 *
 * @param activationKey 激活密钥，非空
 */
public record ActivationConfirmRequest(
        @NotBlank(message = "activationKey 不能为空")
        String activationKey
) {
}
