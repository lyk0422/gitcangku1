package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * 授予乘务资格请求：在指定线路上颁发一个新版本资格，区间左闭右开 [validFromUtc, validToUtc)；
 * validToUtc 传 null 表示长期有效。每次授予产生新版本号，同人员同线路上一 ACTIVE 版本
 * 自动置为 SUPERSEDED。
 */
public record GrantQualificationRequest(
        @NotBlank String lineId,
        @NotNull @PositiveOrZero Integer gradeLevel,
        @NotNull Instant validFromUtc,
        Instant validToUtc) {
}
