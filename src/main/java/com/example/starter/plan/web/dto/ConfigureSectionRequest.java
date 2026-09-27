package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 设置限制区段开放清单请求。requestKey 为幂等键；operator 为操作者标识；
 * allowedClasses 为开放类别清单。空清单、空白类别或重复类别均属配置无效，
 * 由服务层以 422 拒绝并列出明细。
 */
public record ConfigureSectionRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull @Size(max = 100) List<String> allowedClasses) {
}
