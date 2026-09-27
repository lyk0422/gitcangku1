package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换互斥规则集请求。requestKey 为幂等键；operator 为操作者标识；
 * rules 为空表示清空规则集；重复类别对非法。
 */
public record UpdateRulesRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull @Size(max = 500) List<@Valid SeparationRuleRequest> rules) {
}
