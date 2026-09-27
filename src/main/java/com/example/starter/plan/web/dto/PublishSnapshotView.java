package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 发布快照视图：字段固定、按快照追加顺序返回；编组按车厢位置升序，区段路径按字典序。
 * operator 为 null 时序列化为 null（历史请求未登记操作者）；时间为 UTC、毫秒精度。
 */
public record PublishSnapshotView(long id, long planId, String scheduleKey,
                                  java.time.LocalDate opDate, String operator,
                                  String requestKey, String source,
                                  int ruleVersion, int sectionVersion,
                                  List<HazardCarView> formation, List<String> sections,
                                  String validationResult, Instant createdAtUtc) {
}
