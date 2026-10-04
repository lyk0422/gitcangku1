package com.example.starter.session;

import com.example.starter.observation.MergeObservationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 合并观测命令：绑定目标观测记录并包装现有合并请求。
 *
 * @param observationId 目标观测记录唯一标识
 * @param request       现有观测合并请求
 */
public record MergeObservationCommand(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Valid MergeObservationRequest request) implements ObservationCommand {
}