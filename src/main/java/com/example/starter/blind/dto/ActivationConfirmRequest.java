package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 中心激活双人确认请求体。
 *
 * @param activationKey 激活业务键，两名不同未盲法管理人员须提交同一键；
 *                      绑定操作者、中心、代次与全部状态字段
 */
public record ActivationConfirmRequest(
        @NotBlank(message = "activationKey 不能为空")
        @Size(max = 64, message = "activationKey 长度不能超过 64 个字符")
        String activationKey
) {
}
