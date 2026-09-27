package com.example.starter.observation;

/**
 * 采样许可裁决失败原因编码：随 422（及许可交集 409）错误响应返回，保证不同失败分支可程序化区分。
 */
public enum PermitRejectReason {

    /**
     * 无有效许可命中：设备无许可，或许可存在但采集网格不在授权网格区间，或采集时刻不在有效窗口。
     */
    NO_PERMIT,

    /**
     * 命中不唯一：同一设备在该网格与时刻的交集内存在两张有效许可。
     */
    NOT_UNIQUE,

    /**
     * 超出最大观测数：许可当前计数加本次（批量按完整最终计数）后超过 maxObservations。
     */
    LIMIT_EXCEEDED,

    /**
     * 批量项声明的设备/网格/采集时刻与已接收观测行记录的许可采样三元组不一致。
     */
    PERMIT_MISMATCH,

    /**
     * 批量项冲突观测仍存在待裁决（PENDING）冲突簇，不允许重复登记。
     */
    CLUSTER_PENDING,

    /**
     * 创建相交许可：同一设备的有效许可在网格与时间的交集重叠。
     */
    PERMIT_OVERLAP,

    /**
     * 许可状态不允许该操作（如对已撤销许可提前结束或再次撤销）。
     */
    PERMIT_NOT_ACTIVE,

    /**
     * 许可参数的时间区间非法（起点不早于终点、提前结束时刻越界、追溯起点等）。
     */
    INVALID_PERMIT_RANGE
}
