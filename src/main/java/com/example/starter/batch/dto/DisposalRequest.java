package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 隔离处置请求：已装运托盘发现类别错误时进入隔离处置，装载记录保留不删除。
 */
public record DisposalRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空")
        @Size(max = 512, message = "reason 长度不能超过 512")
        String reason
) {
}
