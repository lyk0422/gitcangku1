package com.example.starter.race.persistence;

/**
 * course 表行记录。
 *
 * @param courseKey       赛道标识，全局唯一
 * @param currentRecordId 当前纪录ID（指向 course_record.id）；null 表示尚无纪录
 * @param createdAt       登记时间，Unix毫秒时间戳
 */
public record CourseRow(String courseKey, Long currentRecordId, long createdAt) {
}
