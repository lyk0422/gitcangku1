package com.example.starter.domain;

/**
 * 依赖作用域。
 *
 * <p>PUBLIC：在当前作用域内共享，同一名称全作用域只能有一个精确版本。
 * PRIVATE：为父制品创建以“父作用域 + 父制品名称”定位的子作用域，
 * 其目标及目标的 PUBLIC 闭包仅在该子作用域内共享；同一父制品的多条
 * PRIVATE 边共用同一个子作用域。旧数据未声明作用域时一律视为 PUBLIC。
 */
public enum DependencyScope {
    PUBLIC,
    PRIVATE
}
