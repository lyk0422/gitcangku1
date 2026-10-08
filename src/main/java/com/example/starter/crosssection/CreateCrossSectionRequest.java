package com.example.starter.crosssection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建断面观测点请求：断面号、整数横向毫米、整数高程毫米作为完整版本组一次写入，初始版本为 1。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 断面观测点唯一标识
 * @param sectionNo     断面号（文本原样）
 * @param xMm           横向坐标（整数毫米）
 * @param zMm           地面高程（整数毫米）
 * @param operator      非空操作者标识
 */
public record CreateCrossSectionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Size(max = 64) String sectionNo,
        @NotNull Integer xMm,
        @NotNull Integer zMm,
        @NotBlank @Size(max = 128) String operator) {
}
