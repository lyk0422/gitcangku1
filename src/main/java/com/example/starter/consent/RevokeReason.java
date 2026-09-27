package com.example.starter.consent;

/**
 * 授权撤回原因：USER_REVOKE 用户主动撤回；MIGRATION 跨区域迁移成功时源 epoch 联动撤回。
 */
public enum RevokeReason {
    USER_REVOKE,
    MIGRATION
}
