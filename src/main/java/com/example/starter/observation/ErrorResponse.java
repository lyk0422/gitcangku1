package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 统一错误响应体；conflictFields 与 currentVersion 仅在合并/删除冲突时携带，
 * details 仅在 422 等业务校验失败时携带（逐项原因、实际值/要求值/差额）。
 *
 * @param status         HTTP 状态码
 * @param error          状态短语
 * @param message        错误描述
 * @param conflictFields 三方合并冲突的字段名列表（仅 409 合并冲突时非空）
 * @param currentVersion 冲突时服务端的当前版本号（仅冲突场景非空）
 * @param details        结构化错误细节（仅 422 等校验失败时非空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        List<String> conflictFields,
        Integer currentVersion,
        Map<String, Object> details) {
}