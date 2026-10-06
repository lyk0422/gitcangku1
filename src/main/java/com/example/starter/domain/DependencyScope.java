package com.example.starter.domain;

/**
 * 依赖作用域。
 *
 * <p>PUBLIC：依赖在当前作用域内共享，同一名称只允许一个精确版本；
 * PRIVATE：依赖被隔离到以（父作用域, 父制品名称）定位的子作用域，
 * 其目标及 PUBLIC 闭包只在子作用域内共享，允许制品内部携带另一版本的同名库。
 */
public enum DependencyScope {
    PUBLIC,
    PRIVATE
}
