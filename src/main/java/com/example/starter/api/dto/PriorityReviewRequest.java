package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 带备降优先级的审查提交请求。
 *
 * <p>指纹（requestKey）含优先级、事件号、航线版本与规范化时空段；
 * 同键同参重放首次快照，失败不占键。</p>
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param priority        备降优先级：NORMAL / EMERGENCY
 * @param eventNo         EMERGENCY 必须附的事件编号；NORMAL 必须为空
 * @param segments        规范化时空段采样点（按时间升序，1~50 个）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record PriorityReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotBlank @Size(max = 16) String priority,
        @Size(max = 64) String eventNo,
        @NotNull @Valid @Size(min = 1, max = 50) List<SpaceTimePointDto> segments,
        @NotBlank @Size(max = 64) String requestId) {
}
