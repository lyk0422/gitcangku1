package com.example.starter.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 第一次审核确认请求。审核人不能是任一母样当前保管人。
 *
 * @param commandKey 幂等命令键
 */
public record AliquotFirstConfirmRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
