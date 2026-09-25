package com.example.starter.race.api;

/**
 * 纪录认定被拒绝（422）时的错误响应体，携带赛道实际当前纪录。
 *
 * @param error         错误类型代码
 * @param message       可读错误信息
 * @param currentRecord 赛道当前纪录；尚无纪录时为 null
 */
public record RecordClaimErrorResponse(String error, String message,
                                       CourseRecordResponse currentRecord) {
}
