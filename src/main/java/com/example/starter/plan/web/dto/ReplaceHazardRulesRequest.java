package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换危险品互斥规则集请求。
 * expectedVersion 为当前规则集版本（无配置时传 0），成功后版本加一；规则类别对重复由业务层判 422。
 */
public record ReplaceHazardRulesRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Size(max = 200) List<@Valid HazardRuleRequest> rules) {
}
