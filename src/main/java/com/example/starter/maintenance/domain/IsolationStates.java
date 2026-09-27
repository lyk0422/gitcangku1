package com.example.starter.maintenance.domain;

/**
 * 能源隔离令状态。
 */
public final class IsolationStates {

    /** 已创建隔离令，等待两名不同维护角色确认锁定点。 */
    public static final String PENDING = "PENDING";

    /** 两名角色均已确认，隔离生效；期间设备不得新增运行读数。 */
    public static final String ACTIVE = "ACTIVE";

    /** 所有锁定点逐一解除并登记原因，隔离结束。 */
    public static final String RELEASED = "RELEASED";

    private IsolationStates() {
    }
}
