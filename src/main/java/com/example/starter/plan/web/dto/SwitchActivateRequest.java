package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 激活区段封锁切换单请求：提交完整旧计划集合及其一对一 DRAFT 替代映射。
 * requestKey 为幂等键；映射换序视为同参，必须与预览的相交 PUBLISHED 集合完全一致。
 */
public record SwitchActivateRequest(
        @NotBlank String requestKey,
        @NotEmpty @Valid List<SwitchMappingItem> mappings) {
}
