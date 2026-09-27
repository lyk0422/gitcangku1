package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 创建保养模板请求：初始版本为 1。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param templateId 保养模板标识
 * @param items      检查项定义列表，至少一项；itemCode 不可重复
 */
public record CreateTemplateRequest(
        @NotBlank String requestId,
        @NotBlank String templateId,
        @NotEmpty List<@Valid TemplateItemInput> items) {
}
