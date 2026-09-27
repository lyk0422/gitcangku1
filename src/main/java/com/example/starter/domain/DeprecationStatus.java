package com.example.starter.domain;

/**
 * 弃用窗口状态。
 */
public enum DeprecationStatus {

    /** 生效中或尚未生效（按解析时刻与 effective_at 比较裁决）。 */
    ACTIVE,

    /** 生效前已撤销；记录保留，仅用于历史查询，不再参与锁定裁决。 */
    REVOKED
}
