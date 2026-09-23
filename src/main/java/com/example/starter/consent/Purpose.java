package com.example.starter.consent;

/**
 * 初始用途目录（catalogGeneration=1）内置的用途代码常量。
 *
 * <p>目录迁移后用途代码由用途目录动态管理，不再使用固定枚举；业务代码统一以字符串代码传递用途。
 */
public final class Purpose {

    /** 研究用途（初始目录）。 */
    public static final String RESEARCH = "RESEARCH";

    /** 个性化用途（初始目录）。 */
    public static final String PERSONALIZATION = "PERSONALIZATION";

    private Purpose() {
    }
}
