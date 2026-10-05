package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 批量设置观测禁运请求：一次提交一个或多个簇的禁运截止时刻与披露范围。
 * 任一簇非法时整次回滚（先校验完整最终状态，再在一个事务中提交）。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param items     禁运设置项列表（1~50 条），同一批次内 observationId 不得重复
 */
public record SetEmbargoRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 50) List<@Valid Item> items) {

    /**
     * 单个簇的禁运设置项。
     *
     * @param observationId   观测记录唯一标识（簇）
     * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601）；区间左闭右开，达到该时刻即允许公开发布
     * @param disclosureScope 披露范围标签列表；去空白、去重并排序后存储；为空（或全空白）返回 422
     */
    public record Item(
            @NotBlank @Size(max = 64) String observationId,
            @NotNull Instant embargoUntilUtc,
            @NotNull @Size(max = 16) List<@NotBlank @Size(max = 64) String> disclosureScope) {
    }
}
