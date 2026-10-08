package com.example.starter.crosssection;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 断面冲突显式裁决请求：提交完整候选组与对每个重算冲突字段的人工选择。
 *
 * @param requestId              全局唯一请求标识（幂等去重键）
 * @param resolutionId           全局唯一裁决记录标识
 * @param baseVersion            离线修改所基于的基线版本号（服务端据此重读基线重算冲突）
 * @param expectedCurrentVersion 客户端预期的裁决前当前版本号，不匹配返回 409
 * @param sectionNo              候选断面号完整值
 * @param xMm                    候选横向坐标完整值（整数毫米）
 * @param zMm                    候选地面高程完整值（整数毫米）
 * @param selections             冲突字段选择，键为 sectionNo/xMm/zMm，值为 CURRENT/CANDIDATE，恰好覆盖重算冲突字段
 * @param operator               非空操作者标识
 */
public record ResolveCrossSectionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String resolutionId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Min(1) Integer expectedCurrentVersion,
        @NotNull @Size(max = 64) String sectionNo,
        @NotNull Integer xMm,
        @NotNull Integer zMm,
        @NotNull Map<String, String> selections,
        @NotBlank @Size(max = 128) String operator) {
}
