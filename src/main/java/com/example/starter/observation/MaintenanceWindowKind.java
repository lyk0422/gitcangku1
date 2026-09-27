package com.example.starter.observation;

/**
 * 维护窗口行类型：登记窗口或追加更正窗口。维护窗口只追加、不更新不删除。
 */
public enum MaintenanceWindowKind {
    /**
     * 登记窗口：站点首次登记的一个维护区间。
     */
    REGISTER,
    /**
     * 追加更正窗口：替换既有窗口时间区间，或撤回（retract）既有窗口；本身是一条新的不可变版本行。
     */
    CORRECTION
}
