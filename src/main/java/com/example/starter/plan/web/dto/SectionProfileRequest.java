package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 区段牵引档案写入请求（新增或变更共用）。requestId 为幂等键；operator 为操作者；
 * expectedVersion 为 0 表示新增（档案须不存在），否则必须与当前版本一致，成功加一。
 */
public record SectionProfileRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Boolean electrified,
        @NotNull Boolean dieselAllowed,
        @NotNull @Min(0) Integer expectedVersion) {
}