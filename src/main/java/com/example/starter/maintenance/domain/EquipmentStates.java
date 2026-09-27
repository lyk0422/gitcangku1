package com.example.starter.maintenance.domain;

/**
 * 设备状态。
 */
public final class EquipmentStates {

    /** 在用：可登记读数、保养与能源隔离。 */
    public static final String ACTIVE = "ACTIVE";

    /** 已停用：不可再创建能源隔离令，也不可提交写操作。 */
    public static final String DECOMMISSIONED = "DECOMMISSIONED";

    private EquipmentStates() {
    }
}
