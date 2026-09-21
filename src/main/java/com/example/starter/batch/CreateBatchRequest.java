package com.example.starter.batch;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建批次请求。batchKey 全局唯一；创建后批次字段不可修改。
 *
 * @param commandKey    命令幂等键：同键同参重放返回首次结果，同键改参返回 409
 * @param batchKey      批次业务键，全局唯一
 * @param productCode   产品编码
 * @param lotNumber     批号
 * @param producedAt    生产时间（UTC，ISO-8601，如 2026-09-21T08:00:00Z）
 * @param requiredItems 必做检验项，1～5 个且不得重复
 */
public record CreateBatchRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String batchKey,
        @NotBlank @Size(max = 64) String productCode,
        @NotBlank @Size(max = 64) String lotNumber,
        @NotNull Instant producedAt,
        @NotNull @Size(min = 1, max = 5) List<@NotBlank @Size(max = 64) String> requiredItems) {
}
