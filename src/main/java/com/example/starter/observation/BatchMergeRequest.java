package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量离线合并请求：整批先按完整最终计数、坐标边界与冲突簇预校验，
 * 任一项无许可、命中不唯一或超限返回 422，全部观测与簇变化回滚。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param items     批量合并项（至少一项，按提交顺序裁决）
 */
public record BatchMergeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty @Size(max = 100) List<@Valid BatchMergeItem> items) {
}
