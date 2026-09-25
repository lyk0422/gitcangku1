package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量入库请求。
 * requestId 即全局唯一的 intakeKey，兼做幂等键：同键同参（清单换序视为同参）重放返回首次响应快照，
 * 同键异参返回 409；失败不占用该键。
 * 实测重量列表与清单按下标一一对应，数量必须一致。
 *
 * @param requestId       批量入库幂等键/批次键，全局唯一
 * @param items           证物清单，1~50 件，批内 evidenceKey 不重复且全部不存在于系统
 * @param measuredWeights 实测重量列表，单位千克(kg)，与清单一一对应，1~50 个，大于 0 且最多两位小数
 */
public record BatchIntakeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Valid @NotNull @Size(min = 1, max = 50) List<BatchIntakeItem> items,
        @NotNull @Size(min = 1, max = 50) List<BigDecimal> measuredWeights) {
}
