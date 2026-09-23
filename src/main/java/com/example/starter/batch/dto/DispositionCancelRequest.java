package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 召回处置确认前取消请求体（仅提交人可取消）；取消不改任何批次。
 */
public record DispositionCancelRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
