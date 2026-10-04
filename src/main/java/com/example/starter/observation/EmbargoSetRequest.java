package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量禁运设置请求：先校验全部条目的最终状态，任一非法则整次 422 回滚，不写半成品。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param items     禁运条目列表，1 至 100 条
 */
public record EmbargoSetRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 100) List<@Valid EmbargoItem> items) {
}