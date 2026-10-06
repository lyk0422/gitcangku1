package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 线路图整体替换请求。expectedVersion 必须等于当前版本（无图时为 0），成功版本加一；
 * 站点最多 10 个，有向区段最多 20 条。
 */
public record RouteGraphReplaceRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 10) List<@NotBlank String> stations,
        @NotNull @Size(max = 20) List<@Valid RouteSegmentRequest> segments) {
}
