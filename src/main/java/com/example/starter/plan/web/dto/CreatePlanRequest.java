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
 * <p>车底登记三要素 {@code stockNo}（车底标识）、{@code originStation}（首站）、
 * {@code destinationStation}（末站）要么全部提供（参与车底交路衔接），要么全部为空。
 */
public record CreatePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String scheduleKey,
        @NotNull LocalDate opDate,
        String stockNo,
        String originStation,
        String destinationStation,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies) {
}
