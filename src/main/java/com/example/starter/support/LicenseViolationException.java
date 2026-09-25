package com.example.starter.support;

import com.example.starter.api.dto.LicenseViolation;

import java.util.List;

/**
 * 许可证策略违规异常（HTTP 422）：携带按（名称, 版本）稳定升序的违规列表。
 * 抛出时整次锁定回滚，不生成部分锁文件。
 */
public class LicenseViolationException extends ApiException {

    private final transient List<LicenseViolation> violations;

    public LicenseViolationException(String message, List<LicenseViolation> violations) {
        super(422, "LICENSE_POLICY_VIOLATION", message);
        this.violations = List.copyOf(violations);
    }

    public List<LicenseViolation> getViolations() {
        return violations;
    }
}
