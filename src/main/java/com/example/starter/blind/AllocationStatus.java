package com.example.starter.blind;

/**
 * 参与者分配状态；退组不释放席位。
 */
public enum AllocationStatus {
    /** 在组。 */
    ASSIGNED,
    /** 已退组，席位保留不重排。 */
    WITHDRAWN
}
