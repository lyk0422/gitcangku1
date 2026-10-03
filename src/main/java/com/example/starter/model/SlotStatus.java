package com.example.starter.model;

/**
 * 除冰作业时隙状态：已排定、已完成、已取消。终态不可再变更。
 */
public enum SlotStatus {

    /** 已排定，尚未开始作业，可取消。 */
    SCHEDULED,
    /** 已完成除冰作业，不可取消。 */
    COMPLETED,
    /** 已取消，车辆时隙被释放。 */
    CANCELLED
}