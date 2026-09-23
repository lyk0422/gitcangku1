package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 合规隔离单发起请求：提交某参与者当前完整污染闭包及版本，防过期提交。
 *
 * @param versionNo  提交的闭包版本号；必须等于当前版本号，否则 409
 * @param actors     提交的闭包操作者编号有序去重列表，必须与当前闭包完全一致
 */
public record QuarantineInitiateRequest(
        @NotNull(message = "versionNo 不能为空")
        Integer versionNo,
        List<@NotNull String> actors
) {
}
