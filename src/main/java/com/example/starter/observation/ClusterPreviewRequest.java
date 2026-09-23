package com.example.starter.observation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 重复观测簇候选预览查询：以 observedAt 为锚点，返回同 siteKey + type、
 * 观测时刻与锚点相差不超过 windowSeconds（默认且上限 60 秒）的活跃、未归并、未删除记录。
 * 预览只读、不锁定、不落库，不代表任何后台自动聚类结果。
 *
 * @param siteKey       站点键
 * @param type          观测类型
 * @param observedAt    时间锚点（UTC，ISO-8601）
 * @param windowSeconds 时间窗半宽秒数，取值 1～60，省略时为 60
 */
public record ClusterPreviewRequest(
        @NotBlank @Size(max = 64) String siteKey,
        @NotBlank @Size(max = 64) String type,
        @NotNull Instant observedAt,
        @Min(1) @Max(60) Integer windowSeconds) {

    /**
     * 生效的时间窗半宽：省略时按题设上限 60 秒。
     */
    public int effectiveWindowSeconds() {
        return windowSeconds == null ? 60 : windowSeconds;
    }
}
