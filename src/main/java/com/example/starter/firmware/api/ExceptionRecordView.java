package com.example.starter.firmware.api;

import com.example.starter.firmware.repo.FreezeExceptionRepository;

import java.util.List;

/**
 * 紧急例外放行记录视图。
 */
public record ExceptionRecordView(long id, String api, String incidentId, List<String> approvers,
                                  String ref, String createdAtUtc) {

    public static ExceptionRecordView of(FreezeExceptionRepository.ExceptionRecord record) {
        return new ExceptionRecordView(record.id(), record.api(), record.incidentId(),
                record.approvers(), record.ref(), record.createdAtUtc());
    }
}
