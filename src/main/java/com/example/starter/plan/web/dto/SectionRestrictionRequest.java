package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 限制区段配置项。restricted=true 时 allowedCategories 为开放清单且不允许为空；
 * restricted=false 时开放清单必须为空（区段不限制通行）。
 */
public record SectionRestrictionRequest(
        @NotBlank @Size(max = 64) String sectionId,
        @NotNull Boolean restricted,
        @NotNull @Size(max = 64) List<@NotBlank @Size(max = 64) String> allowedCategories) {
}
