package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 提交观测更正附页请求：原始观测不可覆盖，更正以附页形式追加。
 *
 * @param corrKey     附页幂等键：指纹含原版本、规范化差异、原因与采集者；同键同参重放，异参 409，失败不占键
 * @param baseVersion 附页指定的原观测版本号，必须已存在
 * @param diffs       字段差异（更正值），键仅允许 location/reading/note；空差异或未知字段返回 422
 * @param reason      更正原因
 * @param collector   采集者标识
 */
public record CorrigendumRequest(
        @NotBlank @Size(max = 128) String corrKey,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull Map<String, String> diffs,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 128) String collector) {
}
