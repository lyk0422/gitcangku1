package com.example.starter.domain;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion]。
 *
 * @param optional true 表示可选依赖；false 为必选依赖（历史行为）
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion, boolean optional) {

    /** 兼容旧调用：构造必选依赖。 */
    public DependencyRange(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }
}
