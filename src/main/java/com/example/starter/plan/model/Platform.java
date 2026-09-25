package com.example.starter.plan.model;

/**
 * 站台主数据。
 *
 * @param code            站台代码，全局唯一
 * @param effectiveLength 站台有效长度（辆，与编组长度同单位），正整数
 */
public record Platform(String code, int effectiveLength) {
}
