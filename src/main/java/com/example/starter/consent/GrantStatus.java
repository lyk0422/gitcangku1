package com.example.starter.consent;

/**
 * 授权代次状态；只允许 ACTIVE -> REVOKED 单向迁移。
 */
public enum GrantStatus {

    /** 当前有效，可写入与查询。 */
    ACTIVE,

    /** 已撤回，写入被拒绝、查询返回 410。 */
    REVOKED
}
