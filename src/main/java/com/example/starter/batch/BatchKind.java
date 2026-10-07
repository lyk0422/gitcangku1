package com.example.starter.batch;

/**
 * 批次类型。
 * PLAIN 普通批：无序号域，仅可按数量拆分（原拆批入口），quantity 为 NULL；
 * SERIAL 序号批：根批登记时绑定 1～10 个闭区间整数序号（范围 1..1000000），
 * 区间总长度等于整数数量 quantity，只能经序号区间拆批入口拆分。
 */
public enum BatchKind {
    PLAIN,
    SERIAL
}
