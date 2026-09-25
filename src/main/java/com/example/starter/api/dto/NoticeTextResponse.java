package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 告知文本版本视图，含当前状态与规范化目标地区集合。
 *
 * @param id        文本记录 ID
 * @param noticeKey 告知文本业务标识
 * @param version   文本版本号
 * @param licenseId 对应的许可证标识
 * @param body      告知文本正文
 * @param regions   规范化目标地区代码（大写、升序、去重）
 * @param status    文本状态：DRAFT / APPROVED / WITHDRAWN
 * @param createdAt 首次登记时间，UTC
 * @param updatedAt 最近一次状态或地区变更时间，UTC
 */
public record NoticeTextResponse(
        long id,
        String noticeKey,
        int version,
        String licenseId,
        String body,
        List<String> regions,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
