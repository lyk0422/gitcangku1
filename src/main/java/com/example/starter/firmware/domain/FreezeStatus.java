package com.example.starter.firmware.domain;

/**
 * 冻结令状态：ACTIVE 生效中（含窗口已过但尚未撤销），REVOKED 已撤销（终态）。
 */
public enum FreezeStatus {
    ACTIVE,
    REVOKED
}
