package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 圆弧交集裁定请求：引用 2 至 10 条圆弧观测的当前版本，求全部圆弧的交集。
 * 引用集合顺序可变，但每条引用的观测标识与期望版本不可省略。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param adjudicationId  全局唯一裁定标识
 * @param operator        操作者标识（非空）
 * @param references      参与裁定的观测引用列表（2..10 条，按当前版本核对）
 */
public record AdjudicateArcsRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String adjudicationId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 2, max = 10) List<@Valid ArcReference> references) {

    /**
     * 裁定引用：一条观测记录及其期望当前版本。
     *
     * @param observationId 观测记录唯一标识
     * @param version       期望的当前版本号；与服务器当前版本不一致返回 409
     */
    public record ArcReference(
            @NotBlank @Size(max = 64) String observationId,
            @NotNull @Min(1) Integer version) {
    }
}
