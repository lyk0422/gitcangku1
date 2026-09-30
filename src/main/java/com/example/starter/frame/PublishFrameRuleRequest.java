package com.example.starter.frame;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 发布（新增版本）坐标基准转换规则请求。
 *
 * <p>同一 ruleId 再次发布即生成下一版本：新版本左闭右开地接管从 effectiveFromUtc 起的生效区间，
 * 原开放版本被闭合到该时刻；effectiveFromUtc 必须晚于该链上最新版本的生效起始时刻。
 *
 * @param requestId        全局唯一请求标识（幂等去重键，与观测写入共用 request_log）
 * @param ruleId           规则链标识，同一基准转换规则的各版本共用
 * @param targetDatum      转换目标基准名称
 * @param offsetX          X 方向偏移量，十进制字符串（最多九位小数），目标 X = 原 X + offsetX
 * @param offsetY          Y 方向偏移量，十进制字符串（最多九位小数），目标 Y = 原 Y + offsetY
 * @param effectiveFromUtc 生效起始时刻（UTC，含），ISO-8601 形式，如 2026-09-01T00:00:00Z
 */
public record PublishFrameRuleRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String ruleId,
        @NotBlank @Size(max = 64) String targetDatum,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,9})?", message = "offsetX must be a decimal string with at most 9 fraction digits")
        String offsetX,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,9})?", message = "offsetY must be a decimal string with at most 9 fraction digits")
        String offsetY,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z",
                message = "effectiveFromUtc must be an ISO-8601 UTC instant, e.g. 2026-09-01T00:00:00Z")
        String effectiveFromUtc) {
}
