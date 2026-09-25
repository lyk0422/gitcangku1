package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量曝光预占请求：对同一公告下的多个访客按各自最终账目联合预校验，任一失败整批回滚。
 *
 * @param requestId  写操作全局唯一幂等键；指纹含活动版本、访客集合、类别、时刻与频控字段
 * @param campaignId 公告编号
 * @param visitorIds 访客编号集合（服务端去重并按字典序加锁，避免死锁），1～200 个
 */
public record BatchApplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> visitorIds
) {
}
