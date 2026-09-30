package com.example.starter.plan.model;

/**
 * 供电制式代码。NONE 表示编组无供电需求（任何站台均兼容）。
 */
public enum PowerSupplyType {
    AC25KV,
    DC1500V,
    DC3000V,
    NONE
}
