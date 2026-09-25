package com.example.starter.race.service;

import com.example.starter.race.api.CourseRecordResponse;

/**
 * 纪录认定被拒绝（HTTP 422）：计时未严格优于赛道当前纪录，
 * 或并发下已有更优认定先提交。携带赛道实际当前纪录供客户端核对。
 */
public class RecordClaimRejectedException extends UnprocessableEntityException {

    private final transient CourseRecordResponse currentRecord;

    public RecordClaimRejectedException(String message, CourseRecordResponse currentRecord) {
        super(message);
        this.currentRecord = currentRecord;
    }

    /** 赛道实际当前纪录；尚无纪录时为 null。 */
    public CourseRecordResponse currentRecord() {
        return currentRecord;
    }
}
