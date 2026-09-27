package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 危险品隔离配置版本写入请求：登记互斥类别间隔规则与限制区段开放清单，并指定生效区间。
 * 生效区间左闭右开 [effectiveFromUtc, effectiveToUtc)，均为 UTC；effectiveToUtc 为 null 表示长期有效。
 * requestKey 为幂等键；operator 缺省 system，参与幂等指纹。
 */
public record HazardConfigRequest(
        @NotBlank String requestKey,
        String operator,
        @NotNull Instant effectiveFromUtc,
        Instant effectiveToUtc,
        @NotNull @Size(max = 100) List<@Valid HazardSeparationRuleRequest> separations,
        @NotNull @Size(max = 200) List<@Valid SectionOpenListRequest> restrictedSections) {
}
