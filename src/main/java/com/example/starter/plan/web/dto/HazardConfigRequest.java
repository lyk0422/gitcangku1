package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 危险品配置整体替换请求：隔离规则与限制区段配置先校验完整最终状态，
 * 再在一个事务中提交；任一非法则整体不生效。requestKey 为幂等键，operator 为操作者标识。
 */
public record HazardConfigRequest(
        @NotBlank String requestKey,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(max = 200) List<@Valid SeparationRuleRequest> separationRules,
        @NotNull @Size(max = 200) List<@Valid SectionRestrictionRequest> sectionRestrictions) {
}
