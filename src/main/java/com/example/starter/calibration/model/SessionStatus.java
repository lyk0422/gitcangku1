package com.example.starter.calibration.model;

/**
 * 校准会话状态。
 */
public enum SessionStatus {

    /** 追加中：可继续顺序追加条目。 */
    OPEN,

    /** 已关闭：方向与精确结果冻结，不再追加；可被批量放行引用。 */
    CLOSED,

    /** 已放行：经会话批量放行原子放行，全部成员测量转为已放行。 */
    RELEASED
}
