package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 发布快照视图：编组、类别、规则集版本/哈希、完整区段路径、各区段限制版本与开放清单、校验结论。
 * 字段固定输出，cars 中 hazardClass 为 null 时序列化为 JSON null（普通车厢）。
 */
public record PublishSnapshotView(String scheduleKey,
                                  int planVersion,
                                  List<ConsistCarView> cars,
                                  int ruleVersion,
                                  String rulesHash,
                                  List<String> pathSections,
                                  List<SnapshotSectionView> sectionRestrictions,
                                  String result,
                                  String operator,
                                  Instant createdAt) {
}
