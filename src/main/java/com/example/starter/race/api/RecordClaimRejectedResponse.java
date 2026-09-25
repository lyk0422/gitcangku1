package com.example.starter.race.api;

/**
 * 纪录认定被拒绝（计时不优于当前纪录）的 422 错误体，携带实际当前纪录。
 *
 * @param error         错误类型代码
 * @param message       可读错误信息
 * @param currentRecord 赛道实际当前纪录
 */
public record RecordClaimRejectedResponse(
        String error,
        String message,
        CourseRecordResponse currentRecord
) {
}
