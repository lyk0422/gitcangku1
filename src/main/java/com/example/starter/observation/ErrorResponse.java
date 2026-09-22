package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 统一错误响应体；conflictFields 与 currentVersion 仅在合并/删除冲突时携带。
 *
 * @param status         HTTP 状态码
 * @param error          状态短语
 * @param message        错误描述
 * @param conflictFields 三方合并冲突的字段名列表（仅 409 合并冲突时非空）
 * @param currentVersion 冲突时服务端的当前版本号（仅冲突场景非空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        List<String> conflictFields,
        Integer currentVersion) {
}
