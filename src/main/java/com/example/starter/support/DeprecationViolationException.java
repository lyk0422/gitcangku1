package com.example.starter.support;

import com.example.starter.api.dto.DeprecationViolationView;

import java.util.List;

/**
 * 依赖闭包含已生效弃用版本导致的 422 异常，携带稳定排序的违规明细。
 */
public class DeprecationViolationException extends ApiException {

    private final List<DeprecationViolationView> violations;

    public DeprecationViolationException(List<DeprecationViolationView> violations) {
        super(422, "DEPRECATION_VIOLATION",
                "依赖闭包含 " + violations.size() + " 个已生效弃用版本，无法锁定");
        this.violations = List.copyOf(violations);
    }

    public List<DeprecationViolationView> getViolations() {
        return violations;
    }
}
