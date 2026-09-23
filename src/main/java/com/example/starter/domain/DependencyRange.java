package com.example.starter.domain;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion]。
 *
 * @param optional true 表示可选依赖：必选求解阶段忽略，锁定必选闭包确定后再单独评估
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion, boolean optional) {

    /** 旧签名兼容：未显式指定 optional 时按必选依赖处理。 */
    public DependencyRange(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }
}
