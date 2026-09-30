package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param riskLevel 该版本的依赖风险级别（NONE/LOW/MEDIUM/HIGH/CRITICAL）
 * @param withdrawn true 表示该版本已撤回
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        RiskLevel riskLevel,
        boolean withdrawn,
        List<DependencyRange> dependencies) {

    /** 无风险（NONE）的制品版本快照。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, RiskLevel.NONE, withdrawn, dependencies);
    }
}
