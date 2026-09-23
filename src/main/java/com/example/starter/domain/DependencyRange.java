package com.example.starter.domain;

import java.util.List;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion]。
 *
 * @param optional true=可选依赖（必选解析阶段不参与约束）；false=必选依赖
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion, boolean optional) {

    /** 兼容旧数据与必选依赖的便捷构造器。 */
    public DependencyRange(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }

    public DependencyRange {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("依赖名称不能为空");
        }
        if (minimumVersion > maximumVersion) {
            throw new IllegalArgumentException(
                    "依赖 " + name + " 的最低版本不能高于最高版本");
        }
    }

    /** 返回带 optional 标记的副本。 */
    public DependencyRange withOptional(boolean value) {
        return new DependencyRange(name, minimumVersion, maximumVersion, value);
    }
}
