package com.example.starter.maintenance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 站点观测批量提交请求：可混合多个站点；任一观测格式或站点引用失败整批回滚。
 * 每批最多 15 条（保证幂等重放响应可完整落库）。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param items     批内观测提交项，按提交顺序逐条匹配维护窗口
 */
public record BatchSubmissionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty @Size(max = 15) List<@Valid Item> items) {

    /**
     * 批内单条观测提交。
     *
     * @param submissionId  观测提交唯一标识
     * @param clusterId     冲突簇标识：同簇观测参与同一次合并裁决
     * @param stationId     观测站标识（必须已登记）
     * @param observedAtUtc 观测时刻（UTC）
     * @param location      观测地点
     * @param reading       观测读数，十进制字符串，最多三位小数
     * @param note          观测备注
     */
    public record Item(
            @NotBlank @Size(max = 128) String submissionId,
            @NotBlank @Size(max = 128) String clusterId,
            @NotBlank @Size(max = 64) String stationId,
            @NotNull Instant observedAtUtc,
            @NotNull @Size(max = 512) String location,
            @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
            String reading,
            @NotNull @Size(max = 1024) String note) {
    }
}
