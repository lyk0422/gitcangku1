package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 受试者替补请求体。
 *
 * @param replaceKey        替补业务键，全局唯一，重复使用返回 409
 * @param newParticipantId  替补合成参与者编号；不得已存在于任何区组或拥有历史分配
 */
public record ReplaceRequest(
        @NotBlank(message = "replaceKey 不能为空")
        @Size(max = 64, message = "replaceKey 长度不能超过 64 个字符")
        String replaceKey,
        @NotBlank(message = "newParticipantId 不能为空")
        @Size(max = 64, message = "newParticipantId 长度不能超过 64 个字符")
        String newParticipantId
) {
}
