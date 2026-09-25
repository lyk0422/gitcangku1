package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建冻结令请求。models 与 releaseIds 至少指定一项；服务端规范排序后存储。
 * startUtc/endUtc 为 UTC ISO-8601，左闭右开，结束必须晚于开始。
 */
public record CreateFreezeRequest(
        @NotBlank @Size(max = 64) String freezeKey,
        List<@NotBlank @Size(max = 64) String> models,
        List<@NotNull Long> releaseIds,
        @NotBlank @Size(max = 40) String startUtc,
        @NotBlank @Size(max = 40) String endUtc,
        @Valid EmergencyException exception) {
}
