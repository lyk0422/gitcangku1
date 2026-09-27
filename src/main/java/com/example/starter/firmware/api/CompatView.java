package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CompatRule;

/**
 * 兼容矩阵视图。
 */
public record CompatView(long id, String model, String fromVersion, String toVersion,
                         boolean allowed, String updatedBy) {

    public static CompatView of(CompatRule rule) {
        return new CompatView(rule.id(), rule.model(), rule.fromVersion(), rule.toVersion(),
                rule.allowed(), rule.updatedBy());
    }
}
