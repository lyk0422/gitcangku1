package com.example.starter.race.api;

import java.util.List;

/**
 * 赛道完整历史纪录链响应；按认定先后升序，最后一条为当前纪录。
 *
 * @param courseKey 赛道标识
 * @param records   历史纪录链（只增长不可删改）；无纪录时为空列表
 */
public record RecordHistoryResponse(String courseKey, List<CourseRecordResponse> records) {
}
