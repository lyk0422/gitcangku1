package com.example.starter.plan.web.dto;

import com.example.starter.plan.model.TractionMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 列车牵引档案写入请求（新增或变更共用）。requestId 为幂等键；operator 为操作者；
 * expectedVersion 为 0 表示新增（档案须不存在），否则必须与当前版本一致，成功加一。
 */
public record TrainProfileRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull TractionMode tractionMode,
        @NotNull @Min(0) Integer expectedVersion) {
}