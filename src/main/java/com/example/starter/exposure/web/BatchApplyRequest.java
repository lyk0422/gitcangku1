package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量预占请求：按所有访客的最终账目预校验，任一失败整批回滚，不产生任何预占与账目增量。
 *
 * @param requestId 写操作全局唯一幂等键；整批共用一个键，同键重放返回最初结果
 * @param items     预占条目，每项指定公告与访客，条目数 1～200
 */
public record BatchApplyRequest(
        @jakarta.validation.constraints.NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid @Size(max = 200) List<BatchApplyItem> items
) {
    /**
     * 批量预占条目。
     *
     * @param campaignId 公告编号
     * @param visitorId  访客编号
     */
    public record BatchApplyItem(
            @jakarta.validation.constraints.NotBlank @Size(max = 64) String campaignId,
            @jakarta.validation.constraints.NotBlank @Size(max = 64) String visitorId
    ) {
    }
}
