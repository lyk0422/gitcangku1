package com.example.starter.handoff.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨厂移交发运请求。移交单由路径 manifestKey 给出；seals 必须覆盖冻结清单全部批次
 * （允许换序、不得遗漏/多余/重复），发运在同一事务内把全部批次转为 IN_TRANSIT
 * 并写入封签号与源厂交接快照，任一封签缺失则整单失败、不产生部分发运。
 * requestId 为幂等键。
 */
public record ShipHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "seals 不能为空")
        @Size(min = 1, max = 50, message = "seals 必须包含 1～50 个封签")
        List<@Valid SealSpec> seals
) {

    /**
     * 逐批封签：batchKey 对应冻结清单项，sealNo 为发运封签号，接收时逐批核对。
     */
    public record SealSpec(
            @NotBlank(message = "封签批次 batchKey 不能为空") String batchKey,
            @NotBlank(message = "sealNo 不能为空") String sealNo
    ) {
    }
}
