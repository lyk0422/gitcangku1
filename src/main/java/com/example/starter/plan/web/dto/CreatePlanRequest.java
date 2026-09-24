package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建草稿计划请求。requestKey 为幂等键；占用清单 1～30 条。
 *
 * <p>overnight 为 true 时声明夜间跨零点草稿：占用允许从运营日 22:00 延伸至次日 06:00，
 * 且必须携带 nightPairKey 与次日草稿组成计划对联合发布；非夜间草稿两者均按缺省处理，规则不变。
 *
 * @param overnight    是否夜间跨零点草稿，可空（缺省 false）
 * @param nightPairKey 夜间计划对业务键，仅 overnight=true 时必填，全局唯一
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        Boolean overnight,
        String nightPairKey,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies) {
}
