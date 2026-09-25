package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 批量提交观测更正附页请求：先校验全部原观测、采集者与最终坐标边界，
 * 任一失败整批回滚，不产生任何附页或重算。
 *
 * @param requestId 全局唯一请求标识（整批幂等去重键）
 * @param items     附页条目列表，按提交顺序处理
 */
public record CorrigendumBatchRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty @Size(max = 100) List<@Valid Item> items) {

    /**
     * 批量附页条目。
     *
     * @param corrKey        条目附页幂等键（记录在附页行上，整批幂等由 requestId 保证）
     * @param observationId  观测记录唯一标识
     * @param baseVersion    附页指定的原观测版本号，必须已存在
     * @param diffs          字段差异（更正值），键仅允许 location/reading/note；空差异或未知字段整批 422
     * @param reason         更正原因
     * @param collector      采集者标识
     */
    public record Item(
            @NotBlank @Size(max = 128) String corrKey,
            @NotBlank @Size(max = 64) String observationId,
            @NotNull @Min(1) Integer baseVersion,
            @NotNull Map<String, String> diffs,
            @NotBlank @Size(max = 1024) String reason,
            @NotBlank @Size(max = 128) String collector) {
    }
}
