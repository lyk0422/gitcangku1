package com.example.starter.handoff.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 跨厂移交单创建请求。源厂由 X-Source-Plant 请求头给出；目标厂不得与源厂相同；
 * items 为 1～50 个当前由源厂持有且非 RELEASED 的批次，逐项提交 expectedVersion。
 * requestId 为幂等键；manifestKey 全局唯一；expectedArrivalAt 为 ISO-8601 UTC 时刻。
 */
public record CreateHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "manifestKey 不能为空") String manifestKey,
        @NotBlank(message = "targetPlant 不能为空") String targetPlant,
        @NotNull(message = "expectedArrivalAt 不能为空") Instant expectedArrivalAt,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, max = 50, message = "items 必须包含 1～50 个批次")
        List<@Valid ItemSpec> items
) {

    /**
     * 清单批次规格：batchKey 为待移交批次，expectedVersion 为创建时的批次版本，接收时服务端重新校验。
     */
    public record ItemSpec(
            @NotBlank(message = "批次 batchKey 不能为空") String batchKey,
            @NotNull(message = "expectedVersion 不能为空") Long expectedVersion
    ) {
    }
}
