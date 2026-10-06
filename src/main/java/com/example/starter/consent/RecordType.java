package com.example.starter.consent;

/**
 * 记录类型：TEXT 字符串记录；NUMBER 数值记录（0-1000 非负整数）。
 * 旧字符串记录不自动转数，两种类型共用“主体＋用途＋代次＋记录键”隔离键。
 */
public enum RecordType {
    TEXT,
    NUMBER
}
