package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 圆弧裁定请求：引用 2 至 10 条圆弧观测记录的当前版本，求全部圆弧交集并冻结结果。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param adjudicationId  全局唯一裁定标识，同参重放、改参 409
 * @param operator        操作者标识（非空）
 * @param references      引用的当前版本列表，2 至 10 条；集合顺序可变，字段身份与源版本不可省
 */
public record AdjudicateArcRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String adjudicationId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 2, max = 10) List<ArcSourceRef> references) {
}
