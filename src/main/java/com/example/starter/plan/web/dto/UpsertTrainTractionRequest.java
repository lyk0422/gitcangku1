package com.example.starter.plan.web.dto;

import com.example.starter.plan.model.TractionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新增或变更列车牵引档案请求。requestId 为幂等键；operator 为操作者标识，
 * 与操作、资源及全部参数一起参与幂等身份的结构化比较。
 */
public record UpsertTrainTractionRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull TractionMode tractionMode) {
}