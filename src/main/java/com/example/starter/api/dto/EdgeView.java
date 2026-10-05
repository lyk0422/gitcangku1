package com.example.starter.api.dto;

/**
 * 锁文件中冻结的依赖边：锁定时刻 fromName 对 toName 声明的闭区间约束。
 * 锁定后撤回或新登记不改写该边。
 */
public record EdgeView(String fromName, String toName, int minimumVersion, int maximumVersion) {
}
