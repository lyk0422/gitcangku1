package com.example.starter.blind;

/**
 * 参与者分配状态；退组不释放席位。
 * REPLACED 为替补终态：不落 allocation 表，由 replacement 记录派生，
 * 原分配行原地转移给替补参与者（不新建分配序号）。
 */
public enum AllocationStatus {
    /** 在组。 */
    ASSIGNED,
    /** 已退组，席位保留不重排。 */
    WITHDRAWN,
    /** 已被替补的终态；其区组、席位与处理代码由替补参与者继承。 */
    REPLACED
}
