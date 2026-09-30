package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 一条占用请求项，区间左闭右开，起止均为 UTC 时刻。
 *
 * <p>计划登记了编组时，platformId 必填，表示本次到发停靠的申请站台；
 * 无编组的纯区段占用必须为空。
 *
 * @param trainNo    列车编号
 * @param sectionId  区段 ID
 * @param platformId 申请停靠站台 ID；含编组时必填，无编组时必须为空
 * @param startUtc   占用开始时刻（含，到达），UTC
 * @param endUtc     占用结束时刻（不含，发车），UTC
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        String platformId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc) {
}
