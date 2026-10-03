package com.example.starter.domain;

/**
 * 实际截止时刻的取值依据：SUPPLIER_EXPIRY 供应商瓶身效期；OPEN_EXPIRY 开封后 30 天效期。
 */
public enum DeadlineBasis {
    SUPPLIER_EXPIRY,
    OPEN_EXPIRY
}