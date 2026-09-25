package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建高度层占用请求。仅当关联审查当前仍为 CLEAR 时允许创建；
 * 占用时间窗与巡航高度取自审查快照，不接受客户端另行指定。
 *
 * @param reviewId  关联的审查记录标识
 * @param zoneId    要占用的禁飞区标识（须为审查二维相交区域）
 * @param bandId    要占用的高度带标识（巡航高度须进入该带）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record OccupationCreateRequest(
        @NotBlank @Size(max = 64) String reviewId,
        @NotBlank @Size(max = 64) String zoneId,
        @NotBlank @Size(max = 64) String bandId,
        @NotBlank @Size(max = 64) String requestId) {
}
