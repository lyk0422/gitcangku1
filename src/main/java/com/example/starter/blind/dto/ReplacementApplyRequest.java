package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 受试者替补请求体。
 *
 * @param replaceKey       替补键，必填，参与幂等指纹，仅作业务留痕
 * @param newParticipantId 替补新参与者编号，必填；不得存在于任何区组或拥有历史分配
 */
public record ReplacementApplyRequest(
        @NotBlank(message = "replaceKey 不能为空")
        @Size(max = 64, message = "replaceKey 长度不能超过 64 个字符")
        String replaceKey,
        @NotBlank(message = "newParticipantId 不能为空")
        @Size(max = 64, message = "newParticipantId 长度不能超过 64 个字符")
        String newParticipantId
) {
}
