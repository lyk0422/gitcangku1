package com.example.starter.exposure.domain;

/**
 * 展示位 PO。每个公告创建时默认存在 placementCode={@code DEFAULT}、日额度等于公告日总额度的展示位；
 * 额外展示位最多 20 个（含 DEFAULT），创建后不可修改、不可删除。
 *
 * @param campaignId    所属公告编号
 * @param placementCode 展示位编号，公告内唯一，DEFAULT 为兼容旧申请接口的默认展示位
 * @param dailyCap      该展示位每 UTC 日额度，单位次，取值 1～100000 且不超过公告日总额度
 * @param configVersion 该展示位创建后公告的配置版本号（DEFAULT 为 1，之后每个展示位为递增后的值）
 * @param createdAtUtc  创建时刻（epoch 毫秒，UTC）
 */
public record Placement(
        String campaignId,
        String placementCode,
        int dailyCap,
        int configVersion,
        long createdAtUtc
) {
    /** 兼容旧申请接口的默认展示位编号。 */
    public static final String DEFAULT_CODE = "DEFAULT";
}
