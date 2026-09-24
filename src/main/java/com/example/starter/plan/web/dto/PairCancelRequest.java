package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 夜间计划对取消请求：只取消指定成员计划并释放其自身占用，另一张保持已发布；
 * 不可变计划对记录保留不改写。requestKey 为幂等键。
 *
 * @param scheduleKey 要取消的计划对成员业务键（当日或次日任一已发布成员）
 */
public record PairCancelRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey) {
}
