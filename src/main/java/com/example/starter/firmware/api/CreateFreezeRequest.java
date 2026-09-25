package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建冻结令请求。窗口为 UTC ISO-8601 时刻，左闭右开 [startUtc, endUtc)；
 * 型号与发布单两个集合至少指定一项，服务端去重并规范化排序。
 */
public record CreateFreezeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank String startUtc,
        @NotBlank String endUtc,
        List<@Size(max = 64) String> models,
        List<Long> releaseIds) {

    /**
     * 批量创建中的单项规格（不含 requestId，批量请求整体共用一个 requestId）。
     */
    public record Spec(
            @NotBlank String startUtc,
            @NotBlank String endUtc,
            List<@Size(max = 64) String> models,
            List<Long> releaseIds) {
    }

    /**
     * 批量创建冻结令请求，任一项冲突或非法则整体 422、全部不写入。
     */
    public record Batch(
            @NotBlank @Size(max = 64) String requestId,
            @NotEmpty @Valid List<Spec> items) {
    }
}
