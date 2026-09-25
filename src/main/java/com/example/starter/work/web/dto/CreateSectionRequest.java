package com.example.starter.work.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册区段请求。区段需先注册，施工单区段集合中的每个区段都必须已存在。
 */
public record CreateSectionRequest(@NotBlank String sectionId) {
}
