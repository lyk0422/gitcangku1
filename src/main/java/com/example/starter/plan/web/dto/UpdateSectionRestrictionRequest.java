package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换某限制区段危险品开放清单请求。
 * expectedVersion 为该区段当前版本（未配置时传 0），成功后版本加一。
 * allowedClasses 为空列表表示该区段不允许任何危险品类别通过；类别重复由业务层判 422。
 */
public record UpdateSectionRestrictionRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Size(max = 200) List<@NotBlank String> allowedClasses) {
}
