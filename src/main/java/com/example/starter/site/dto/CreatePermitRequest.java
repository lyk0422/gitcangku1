package com.example.starter.site.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建作业许可请求。必须引用 1～20 个已安装隔离记录，且其区间完整覆盖作业区间。
 */
public record CreatePermitRequest(
        @NotBlank String commandKey,
        @NotBlank String permitKey,
        @NotBlank String crewName,
        @NotNull Instant workStartUtc,
        @NotNull Instant workEndUtc,
        @NotBlank String applicant,
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> isolationKeys) {
}
