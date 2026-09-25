package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 提交更正附页请求：指定原观测版本、字段差异（字段名 → 更正值）、原因和采集者。
 *
 * @param corrKey     幂等键：指纹含原版本、差异规范化、原因和采集者；同键同参重放，失败不占键
 * @param baseVersion 原观测版本号，须等于提交时观测当前版本，否则 409
 * @param diffs       字段差异：键仅允许 location/reading/note，值为更正值；空差异或未知字段返回 422
 * @param reason      更正原因
 * @param collector   采集者标识
 */
public record SubmitCorrigendumRequest(
        @NotBlank @Size(max = 128) String corrKey,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull Map<String, String> diffs,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 128) String collector) {
}
