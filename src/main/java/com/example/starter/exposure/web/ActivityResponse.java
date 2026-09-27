package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Activity;

/**
 * 活动视图。
 *
 * @param activityId   活动编号
 * @param version      预算配置乐观锁版本号，每次配置变更 +1
 * @param createdAtUtc 创建时刻，epoch 毫秒，UTC
 */
public record ActivityResponse(
        String activityId,
        int version,
        long createdAtUtc
) {
    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(
                activity.activityId(),
                activity.version(),
                activity.createdAtUtc());
    }
}
