package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 请求报文载体。 */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** 电芯入批请求：初始电压单位伏特(V)。 */
    public record IntakeRequest(@NotBlank String requestId,
                                @NotBlank String cellCode,
                                @NotNull Double initialVoltage) {
    }

    /** 终测读数请求：measuredAt 为 UTC 测量时刻，须晚于入批时刻。 */
    public record FinalReadingRequest(@NotBlank String requestId,
                                      @NotBlank String cellCode,
                                      @NotNull Double voltage,
                                      @NotNull Instant measuredAt) {
    }

    /** 隔离请求。 */
    public record IsolateRequest(@NotBlank String cellCode,
                                 @NotBlank String reason) {
    }

    /** 交接签收请求：交出方与接收方。 */
    public record HandoverRequest(@NotBlank String requestId,
                                  @NotBlank String fromParty,
                                  @NotBlank String toParty) {
    }
}