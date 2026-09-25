package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 访客抑制名单批量更新请求。整批原子生效：先按活动当前版本乐观校验，
 * 再校验完整最终区间集合；任一区间重叠或起止非法整批 422，原名单不变。
 *
 * @param requestId          写操作全局唯一幂等键
 * @param expectedVersion    调用方认知的公告版本（名单版本）；与当前版本不一致返回 409
 * @param addIntervals       本批新增区间；为空表示不新增
 * @param terminateIntervals 本批对已存在区间的处置（删除未开始 / 提前结束已开始）；为空表示无
 */
public record SuppressionBatchUpdateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Integer expectedVersion,
        @Valid List<SuppressionAddSpec> addIntervals,
        @Valid List<SuppressionTerminateSpec> terminateIntervals
) {
}
