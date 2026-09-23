package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 隔离单发起请求体：合规负责人提交其查询到的参与者“当前完整污染闭包”及版本号，
 * 服务端重新计算并比对，闭包或版本已变化（旧闭包）时拒绝。
 *
 * @param version 提交时的闭包版本号
 * @param actors  提交时的完整污染闭包操作者集合（去重排序）
 */
public record QuarantineCreateRequest(
        @NotNull(message = "version 不能为空")
        Integer version,
        @NotEmpty(message = "actors 不能为空")
        List<@NotBlank(message = "actor 不能为空")
                @Size(max = 64, message = "actor 最长 64 字符") String> actors
) {
}
