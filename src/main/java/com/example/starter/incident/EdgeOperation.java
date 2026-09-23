package com.example.starter.incident;

/**
 * 提案边操作类型：ADD 新增依赖边，REMOVE 删除依赖边。
 */
public enum EdgeOperation {

    /** 新增一条依赖边。 */
    ADD,

    /** 删除一条现存依赖边。 */
    REMOVE;
}
