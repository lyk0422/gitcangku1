package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 修订保养模板请求：整体替换检查项集合，生成递增模板版本；历史版本不改写。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param items     新版本的检查项定义列表，至少一项；itemCode 不可重复
 */
public record ReviseTemplateRequest(
        @NotBlank String requestId,
        @NotEmpty List<@Valid TemplateItemInput> items) {
}
