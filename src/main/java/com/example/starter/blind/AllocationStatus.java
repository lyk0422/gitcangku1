package com.example.starter.blind;

/**
 * 参与者分配状态；退组不释放席位。
 */
public enum AllocationStatus {
    /** 在组。 */
    ASSIGNED,
    /** 已退组，席位保留不重排。 */
    WITHDRAWN,
    /** 已被替补（终态）：原参与者的分配序号由替补参与者继承，原参与者仅保留于替补记录。 */
    REPLACED
}
