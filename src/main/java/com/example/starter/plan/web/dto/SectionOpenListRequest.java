package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.SortedSet;

/**
 * 限制区段开放清单请求项：sectionId 为限制区段，openHazardClasses 为该区段允许通过的危险品类别集合。
 * 开放清单为空集合表示登记了限制区段但无任何类别可通过，属无效配置（发布/改签返回 422）。
 *
 * @param sectionId         限制区段 ID
 * @param openHazardClasses 开放的危险品类别集合，服务端按字典序规范化
 */
public record SectionOpenListRequest(
        @NotBlank String sectionId,
        SortedSet<@NotBlank String> openHazardClasses) {
}
