package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建披露包请求。packageKey 同时作为建包幂等键：同键同指纹重放返回首次结果，
 * 同键改参返回 409，失败不占键。证物集合在服务端规范化（去重、字典序排序）。
 *
 * @param packageKey   披露包业务键，全局唯一
 * @param caseKey      所属案件键，包内全部证物必须属于该案件
 * @param revision     包版本（修订号），必须严格大于该案件已有最高版本
 * @param asOf         截至时刻（UTC），全部历史状态按该时刻重放判定
 * @param evidenceKeys 证物键集合，非空；重复键将被去重
 */
public record PackageCreateRequest(
        @NotBlank @Size(max = 64) String packageKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotNull @Min(1) Integer revision,
        @NotNull LocalDateTime asOf,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
