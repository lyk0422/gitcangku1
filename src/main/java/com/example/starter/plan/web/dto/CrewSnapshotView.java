package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划乘务组发布快照：值乘线路、要求等级与逐人资格版本。
 * 草稿与未携带乘务组发布的计划该字段为 null；发布后不可变。
 */
public record CrewSnapshotView(String lineId, int requiredGrade,
                               List<CrewAssignmentView> members) {
}
