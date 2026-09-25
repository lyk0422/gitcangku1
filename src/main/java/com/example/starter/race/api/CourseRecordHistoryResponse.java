package com.example.starter.race.api;

import java.util.List;

/**
 * 赛道完整历史纪录链响应（只读，不触发认定）。
 *
 * @param courseKey 赛道标识
 * @param current   当前纪录；尚无纪录时为 null
 * @param history   历史链，按链内序号升序（首项为最早纪录），只增长不可删改
 */
public record CourseRecordHistoryResponse(String courseKey, CourseRecordResponse current,
                                          List<CourseRecordResponse> history) {
}
