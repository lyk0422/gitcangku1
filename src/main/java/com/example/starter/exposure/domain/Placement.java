package com.example.starter.exposure.domain;

/**
 * 公告展示位 PO。每个公告创建时同事务创建 code={@code DEFAULT}、
 * 日额度等于公告日总额度的默认展示位；之后最多再创建到合计 20 个唯一 code。
 * 展示位创建后不可修改或删除。
 *
 * @param campaignId    所属公告编号
 * @param placementCode 展示位编号；公告内唯一，{@code DEFAULT} 为默认展示位
 * @param dailyCap      该展示位每 UTC 日额度，单位次，取值 1～100000 且不超过公告日总额度
 * @param configVersion 该展示位落库后公告的配置版本号（DEFAULT=1，其后逐个递增）
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
