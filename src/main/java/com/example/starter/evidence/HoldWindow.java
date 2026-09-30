package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 冻结有效期判定。窗口左闭右开 [createdUtc, expiresUtc)：
 * createdUtc &lt;= now &lt; expiresUtc 且冻结未解除时有效；now 恰等于 expiresUtc 时已失效。
 */
public final class HoldWindow {

    private HoldWindow() {
    }

    /**
     * 判断冻结在指定 UTC 时刻是否仍有效：未解除、未早于创建时刻、且严格早于到期时刻。
     */
    public static boolean isEffective(HoldStatus status, LocalDateTime createdUtc,
                                      LocalDateTime expiresUtc, LocalDateTime nowUtc) {
        return status == HoldStatus.ACTIVE
                && !nowUtc.isBefore(createdUtc)
                && nowUtc.isBefore(expiresUtc);
    }
}
