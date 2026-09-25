package com.example.starter.workblock.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 修改施工占用窗口请求。expectedVersion 必须与当前版本一致，成功后版本加一；
 * 修改将以本请求的时段与区段集合整体替换旧值，并重新校验全部已发布计划。
 */
public record UpdateWorkBlockRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotEmpty List<@NotBlank String> sectionIds,
        @NotBlank String operator) {
}
