package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 生产线上机登记请求。requestId 为幂等键；expectedSeq 为期望的加工顺序号，
 * 必须等于该生产线当前最大顺序加一（首批为 1），否则视为插队返回 409。
 */
public record LineRunRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotNull(message = "expectedSeq 不能为空") Integer expectedSeq
) {
}