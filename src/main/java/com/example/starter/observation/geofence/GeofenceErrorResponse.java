package com.example.starter.observation.geofence;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 地理围栏 422 错误响应体：携带全部校验失败明细（实际值、要求值、差额）。
 *
 * @param status  HTTP 状态码（422）
 * @param error   状态短语
 * @param message 错误描述
 * @param details 校验失败明细列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeofenceErrorResponse(
        int status,
        String error,
        String message,
        List<ValidationDetail> details) {
}
