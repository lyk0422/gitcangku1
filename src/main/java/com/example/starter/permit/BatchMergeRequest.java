package com.example.starter.permit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量离线合并请求：离线采集的观测整批提交并合并。
 *
 * <p>服务端在任何写入前完成全部预校验：每条观测唯一命中许可、坐标落在命中许可网格内、
 * 按许可汇总的完整最终计数不超过上限、冲突簇引用合法且未被之前批次提交或裁决；
 * 任一不满足整批 422 且全部回滚。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param items     批次内全部观测（含冲突簇内的每条观测）
 * @param clusters  离线端检测出的冲突簇；无冲突时为空列表
 */
public record BatchMergeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty @Valid List<MergeItem> items,
        @NotNull @Valid List<ClusterSpec> clusters) {

    /**
     * 批次内单条观测：坐标与采集时刻用于命中许可，content 为合并后最终内容。
     */
    public record MergeItem(
            @NotBlank @Size(max = 64) String deviceId,
            @NotNull java.math.BigDecimal gridLon,
            @NotNull java.math.BigDecimal gridLat,
            @NotNull java.time.Instant sampledAt,
            @NotBlank @Size(max = 2048) String content) {
    }

    /**
     * 冲突簇：clusterId 全局不可复用，observationKeys 为簇内观测业务键（至少 2 条）。
     */
    public record ClusterSpec(
            @NotBlank @Size(max = 64) String clusterId,
            @NotEmpty @Size(min = 2, max = 64) List<@NotBlank String> observationKeys) {
    }
}
