package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 召回处置单二审确认请求体。处置单由路径给出（必须为 SUBMITTED）。
 * 确认人通过 X-Actor-Id（OPERATIONS，必须与提交人不同）提供；requestId 为确认命令幂等键。
 * expectedDispositionVersion 必须为提交时版本（1），否则 409；
 * batchVersions 携带处置单冻结的全部批次业务键与提交时版本，二审时重新计算闭包并逐批比对，
 * 期间发生拆分、再次召回、状态或路径变化均 409。集合内部顺序无关。
 */
public record DispositionConfirmRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "expectedDispositionVersion 不能为 null") Integer expectedDispositionVersion,
        @NotNull(message = "batchVersions 不能为 null")
        @Size(min = 1, max = 1000, message = "batchVersions 必须包含冻结的全部批次")
        List<@NotNull BatchVersion> batchVersions
) {

    /**
     * 二审携带的单个批次版本断言。
     */
    public record BatchVersion(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotNull(message = "version 不能为 null") Long version
    ) {
    }
}
