package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 批量离线合并请求：一批设备离线采集的观测整批提交。
 *
 * <p>每个项携带完整最终计数所需的许可采样三元组（设备、网格、采集时刻）与最终字段值；
 * baseVersion 为空（或 0）表示新观测（创建并扣减许可计数），否则对已存在观测执行三方合并。
 * 整批先预校验（许可命中唯一性、按许可汇总的完整最终计数、网格边界、冲突簇），
 * 任一观测无许可/命中不唯一/超限即 422，全部观测与簇变化随事务回滚。
 *
 * @param requestId 全局唯一批量请求标识（幂等去重键）
 * @param items     批量项列表，不允许为空
 */
public record BatchMergeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Size(min = 1, max = 1000) @Valid List<Item> items) {

    /**
     * 批量离线合并单项。
     *
     * @param observationId 观测记录唯一标识
     * @param baseVersion   三方合并基线版本号；null 或 0 表示新观测
     * @param deviceId      采样设备标识
     * @param gridId        采集网格单元编号
     * @param observedAtUtc 采集时刻（UTC）
     * @param location      最终观测地点
     * @param reading       最终观测读数（十进制原文，最多三位小数）
     * @param note          最终观测备注
     */
    public record Item(
            @NotBlank @Size(max = 64) String observationId,
            Integer baseVersion,
            @NotBlank @Size(max = 64) String deviceId,
            @NotNull Integer gridId,
            @NotNull Instant observedAtUtc,
            @NotNull @Size(max = 512) String location,
            @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
            String reading,
            @NotNull @Size(max = 1024) String note) {
    }
}
