package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量离线观测提交请求：可混合站点，按 items 顺序裁决；任一明细不合法则整批回滚，不产生半成品。
 * 批量上限 20 条：成功响应快照需完整存入 request_log 用于同参重放。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  提交操作者标识
 * @param items     提交明细，按提交顺序裁决
 */
public record BatchGeofenceSubmissionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 20) List<@Valid GeofenceSubmissionItem> items) {
}
