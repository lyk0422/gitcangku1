package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 提交完整旧计划集合与一对一替代草稿映射。必须覆盖封锁窗口当前相交的全部 PUBLISHED 计划；
 * 仅 REGISTERED 切换单可提交，重复提交整体替换既有映射。
 */
public record DisruptionSubmitRequest(
        @NotNull @NotEmpty List<@Valid DisruptionMappingItem> mappings) {
}
