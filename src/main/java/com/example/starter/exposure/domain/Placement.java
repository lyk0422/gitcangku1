package com.example.starter.exposure.domain;

/**
 * 展示位 PO。公告下展示位以 (campaignId, placementCode) 唯一，创建后不可修改、不可删除。
 * 公告创建时自动生成 {@code DEFAULT} 展示位（configVersion=1，日额度等于公告日总额度）。
 *
 * @param campaignId    所属公告编号
 * @param placementCode 展示位编号，公告内唯一，{@code DEFAULT} 为兼容旧申请接口的默认展示位
 * @param dailyCap      该展示位每 UTC 日额度，单位次，取值 1～100000 且不超过公告日总额度
 * @param configVersion 该展示位创建后公告达到的配置版本号（DEFAULT 为 1，之后逐个递增）
 * @param createdAtUtc  创建时刻（epoch 毫秒，UTC）
 */
public record Placement(
        String campaignId,
        String placementCode,
        int dailyCap,
        int configVersion,
        long createdAtUtc
) {
    /** 默认展示位编号；旧申请接口等价于申请该展示位。 */
    public static final String DEFAULT_CODE = "DEFAULT";
}
