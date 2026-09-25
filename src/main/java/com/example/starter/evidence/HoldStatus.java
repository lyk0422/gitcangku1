package com.example.starter.evidence;

/**
 * 保全冻结状态。
 * ACTIVE 有效（解除前参与重叠校验与销毁门禁）；RELEASED 已解除（终态，不再阻断销毁）。
 */
public enum HoldStatus {
    ACTIVE,
    RELEASED
}
