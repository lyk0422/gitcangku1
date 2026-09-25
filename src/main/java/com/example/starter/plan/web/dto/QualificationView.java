package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 乘务资质视图。
 *
 * @param qualCode   资质代码
 * @param crewId     乘务员标识
 * @param sections   覆盖区段集合（规范化：排序去重）
 * @param expiresUtc 到期时刻（UTC）
 * @param version    当前版本
 * @param terminated 是否已提前终止
 */
public record QualificationView(String qualCode, String crewId, List<String> sections,
                                Instant expiresUtc, int version, boolean terminated) {
}
