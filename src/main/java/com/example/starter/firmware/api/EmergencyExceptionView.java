package com.example.starter.firmware.api;

import com.example.starter.firmware.repo.EmergencyExceptionRepository.EmergencyException;

/**
 * 紧急双人例外放行记录视图。
 */
public record EmergencyExceptionView(long exceptionId, long freezeId, String eventNo, String confirmer1,
                                     String confirmer2, String operation, String reference,
                                     String requestId, String createdAtUtc) {

    public static EmergencyExceptionView of(EmergencyException e) {
        return new EmergencyExceptionView(e.id(), e.freezeId(), e.eventNo(), e.confirmer1(), e.confirmer2(),
                e.operation(), e.reference(), e.requestId(), e.createdAtUtc());
    }
}
