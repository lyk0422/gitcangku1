package com.example.starter.testsupport;

import com.example.starter.web.dto.ApplyRequest;

import java.time.Instant;

/**
 * 测试用申请构造工具。
 */
public final class Requests {

    public static final String INSTRUMENT_T = "T";
    public static final String GROUP_A = "GROUP-A";
    public static final String GROUP_B = "GROUP-B";

    private Requests() {
    }

    public static ApplyRequest of(String project, String instrumentId, String researchGroup,
                                  Instant windowStart, Instant windowEnd, int durationMinutes,
                                  String requestId) {
        return new ApplyRequest(project, instrumentId, researchGroup,
                windowStart, windowEnd, durationMinutes, requestId);
    }

    public static ApplyRequest onInstrumentT(String project, String group,
                                             Instant windowStart, Instant windowEnd,
                                             int durationMinutes) {
        return of(project, INSTRUMENT_T, group, windowStart, windowEnd, durationMinutes, null);
    }
}
