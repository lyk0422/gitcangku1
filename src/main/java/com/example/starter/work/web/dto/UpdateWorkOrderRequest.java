package com.example.starter.work.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 修改施工单请求：整体替换窗口与区段集合，版本加一；expectedVersion 乐观校验。
 * 修改必须重校验全部已发布计划，若造成冲突则 422 且不部分生效。
 */
public record UpdateWorkOrderRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull @Size(min = 1, max = 30) List<@NotBlank String> sectionIds) {
}
