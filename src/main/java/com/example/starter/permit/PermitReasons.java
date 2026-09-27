package com.example.starter.permit;

/**
 * 采样许可相关失败的机器可区分原因码；所有许可、提交、合并、撤销与裁决失败均携带其中之一。
 */
public final class PermitReasons {

    /** 许可范围与同一设备已有有效许可在网格与时间上交集重叠（创建返回 409）。 */
    public static final String PERMIT_OVERLAP = "PERMIT_OVERLAP";
    /** 许可标识已存在（创建返回 409）。 */
    public static final String PERMIT_ID_DUPLICATE = "PERMIT_ID_DUPLICATE";
    /** 同一设备的许可版本号已被使用（创建返回 409）。 */
    public static final String PERMIT_VERSION_DUPLICATE = "PERMIT_VERSION_DUPLICATE";
    /** 许可不存在（查询/操作返回 404）。 */
    public static final String PERMIT_NOT_FOUND = "PERMIT_NOT_FOUND";
    /** 许可已提前结束，拒绝再次结束或撤销等状态流转（返回 409）。 */
    public static final String PERMIT_ALREADY_ENDED = "PERMIT_ALREADY_ENDED";
    /** 许可已撤销，拒绝再次结束或撤销（返回 409）。 */
    public static final String PERMIT_ALREADY_REVOKED = "PERMIT_ALREADY_REVOKED";
    /** 采集时刻无任何时间有效的设备许可（提交/合并返回 422）。 */
    public static final String NO_TIME_VALID_PERMIT = "NO_TIME_VALID_PERMIT";
    /** 存在时间有效许可但采集网格点不落在任一许可网格闭矩形内（返回 422）。 */
    public static final String GRID_OUT_OF_BOUNDS = "GRID_OUT_OF_BOUNDS";
    /** 网格与时间上同时命中多个有效许可（返回 422）。 */
    public static final String PERMIT_NOT_UNIQUE = "PERMIT_NOT_UNIQUE";
    /** 命中许可的已接收计数将超过最大观测数（返回 422）。 */
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    /** 采集时刻晚于提交时刻，禁止以未来采样命中许可（返回 422）。 */
    public static final String SAMPLED_AT_IN_FUTURE = "SAMPLED_AT_IN_FUTURE";
    /** 同一观测业务键已被接收（返回 409）。 */
    public static final String OBSERVATION_ALREADY_RECEIVED = "OBSERVATION_ALREADY_RECEIVED";
    /** 批量合并中观测业务键在批次内重复（返回 422）。 */
    public static final String DUPLICATE_IN_BATCH = "DUPLICATE_IN_BATCH";
    /** 冲突簇已在之前的合并批次中提交（返回 422，禁止重算）。 */
    public static final String CLUSTER_ALREADY_MERGED = "CLUSTER_ALREADY_MERGED";
    /** 冲突簇已经人工裁决（返回 409，不重算）。 */
    public static final String CLUSTER_ALREADY_RESOLVED = "CLUSTER_ALREADY_RESOLVED";
    /** 裁决选定的观测不属于该冲突簇（返回 422）。 */
    public static final String WINNER_NOT_IN_CLUSTER = "WINNER_NOT_IN_CLUSTER";
    /** 冲突簇不存在（裁决返回 404）。 */
    public static final String CLUSTER_NOT_FOUND = "CLUSTER_NOT_FOUND";
    /** 许可观测不存在（查询返回 404）。 */
    public static final String OBSERVATION_NOT_FOUND = "OBSERVATION_NOT_FOUND";

    private PermitReasons() {
    }
}
