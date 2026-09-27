package com.example.starter.sampling;

/**
 * 采样许可失败原因码：失败原因须可区分，随错误响应与拒绝记录返回。
 */
public final class SamplingReasons {

    /** 设备、网格与采集时刻未命中任何有效许可。 */
    public static final String NO_PERMIT = "NO_PERMIT";
    /** 命中多个有效许可，无法唯一裁决。 */
    public static final String PERMIT_NOT_UNIQUE = "PERMIT_NOT_UNIQUE";
    /** 接收后许可计数将超过最大观测数上限。 */
    public static final String PERMIT_LIMIT_EXCEEDED = "PERMIT_LIMIT_EXCEEDED";
    /** 提交携带的许可版本与命中许可版本不一致。 */
    public static final String PERMIT_VERSION_MISMATCH = "PERMIT_VERSION_MISMATCH";
    /** 网格坐标超出采样网格全局边界。 */
    public static final String GRID_OUT_OF_BOUNDS = "GRID_OUT_OF_BOUNDS";
    /** 与同设备有效许可在网格与时间的交集上重叠。 */
    public static final String PERMIT_OVERLAP = "PERMIT_OVERLAP";
    /** 许可标识已存在。 */
    public static final String PERMIT_EXISTS = "PERMIT_EXISTS";
    /** 许可当前状态不允许该操作（已结束或已撤销）。 */
    public static final String PERMIT_NOT_ACTIVE = "PERMIT_NOT_ACTIVE";
    /** 批次标识被不同内容复用。 */
    public static final String BATCH_REUSED = "BATCH_REUSED";
    /** 冲突簇不处于待裁决状态。 */
    public static final String CLUSTER_NOT_PENDING = "CLUSTER_NOT_PENDING";

    private SamplingReasons() {
    }
}
