package com.example.starter.evidence.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅需幂等键的命令请求（交接接受/取消）。
 */
public record CommandRequest(
        @NotBlank(message = "不能为空") @Size(max = 64) String commandKey
) {
}
