package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 批量离线合并项：对一条已存在观测记录的三方合并候选，携带设备、采集网格与采集时刻
 * 用于许可命中与计数扣减。
 *
 * @param observationId  观测记录唯一标识
 * @param baseVersion    离线修改所基于的基线版本号
 * @param deviceId       采集设备标识
 * @param gridX          采集网格 X 坐标
 * @param gridY          采集网格 Y 坐标
 * @param collectedAtUtc 采集时刻（UTC）
 * @param location       观测地点候选值
 * @param reading        观测读数候选值，十进制字符串，最多三位小数
 * @param note           观测备注候选值
 */
public record BatchMergeItem(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Min(1) Integer baseVersion,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull Integer gridX,
        @NotNull Integer gridY,
        @NotNull Instant collectedAtUtc,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
