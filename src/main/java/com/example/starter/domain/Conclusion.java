package com.example.starter.domain;

/**
 * 审核结论：CLEAR 表示航线与所有有效禁飞区无接触；BLOCKED 表示存在相交、位于其中或仅接触边界。
 */
public enum Conclusion {
    CLEAR,
    BLOCKED
}
