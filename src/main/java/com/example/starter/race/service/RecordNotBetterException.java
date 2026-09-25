package com.example.starter.race.service;

import com.example.starter.race.api.CourseRecordResponse;

/**
 * 纪录认定被拒绝：申请计时不严格优于赛道当前纪录（HTTP 422），
 * 携带认定事务内读到的实际当前纪录。
 */
public class RecordNotBetterException extends RuntimeException {

    private final CourseRecordResponse currentRecord;

    public RecordNotBetterException(String message, CourseRecordResponse currentRecord) {
        super(message);
        this.currentRecord = currentRecord;
    }

    /** 赛道实际当前纪录。 */
    public CourseRecordResponse currentRecord() {
        return currentRecord;
    }
}
