package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建跨厂移交单请求。源厂一次选择 1～50 个当前由本厂持有且非 RELEASED 的批次；
 * 每个批次提交 expectedVersion（创建时须与批次当前版本一致）。
 * 清单按 batchKey 字典序冻结，换序重放视为同参。
 */
public record CreateHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "manifestKey 不能为空") String manifestKey,
        @NotBlank(message = "sourcePlant 不能为空") String sourcePlant,
        @NotBlank(message = "targetPlant 不能为空") String targetPlant,
        @NotNull(message = "expectedArrivalAt 不能为空") Instant expectedArrivalAt,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, max = 50, message = "items 必须包含 1～50 个批次")
        List<@Valid Item> items
) {

    /**
     * 移交清单项：批次业务键 + 期望版本号。
     */
    public record Item(
            @NotBlank(message = "批次 batchKey 不能为空") String batchKey,
            @NotNull(message = "expectedVersion 不能为空")
            @Min(value = 0, message = "expectedVersion 不能为负数") Integer expectedVersion
    ) {
    }
}
