package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 已装运托盘发现类别错误后的隔离处置请求；不删除任何装载记录，只把托盘状态置为 QUARANTINED 并记录事件。
 */
public record QuarantinePalletRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空")
        @Size(max = 512, message = "reason 长度不能超过 512")
        String reason
) {
}
