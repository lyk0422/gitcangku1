package com.example.starter.crosssection;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 断面观测点离线提交（整组三方合并）请求：断面号/横向/高程必须作为完整候选组提交，不接受部分字段。
 *
 * @param requestId   全局唯一请求标识（幂等去重键；冲突失败不占键）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param sectionNo   候选断面号完整值
 * @param xMm         候选横向坐标完整值（整数毫米）
 * @param zMm         候选地面高程完整值（整数毫米）
 * @param operator    非空操作者标识
 */
public record MergeCrossSectionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Size(max = 64) String sectionNo,
        @NotNull Integer xMm,
        @NotNull Integer zMm,
        @NotBlank @Size(max = 128) String operator) {
}
