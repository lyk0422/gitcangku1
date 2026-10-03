package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Activity;

/**
 * 活动视图。
 *
 * @param activityId   活动编号
 * @param createdAtUtc 创建时刻，epoch 毫秒，UTC
 */
public record ActivityResponse(
        String activityId,
        long createdAtUtc
) {
    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(activity.activityId(), activity.createdAtUtc());
    }
}