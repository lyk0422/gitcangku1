package com.example.starter.exposure.web;

import java.util.List;

/**
 * 身份归并组视图。
 *
 * @param groupId          归并组编号
 * @param version          当前版本号：创建为 1，每次成员变更 +1
 * @param memberVisitorIds 组内成员编号，按字典序升序；上限 10 个
 * @param changed          本次归并是否变更了成员关系（重复归并已同组的两编号为 false）
 * @param operatedAtUtc    归并操作时刻（查询时为组创建时刻），epoch 毫秒，UTC
 */
public record IdentityGroupResponse(
        String groupId,
        int version,
        List<String> memberVisitorIds,
        boolean changed,
        long operatedAtUtc
) {
}
