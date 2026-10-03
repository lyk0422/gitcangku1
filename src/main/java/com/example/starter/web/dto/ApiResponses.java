package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/** 响应报文载体；时刻均为 UTC，电压单位伏特(V)。 */
public final class ApiResponses {

    private ApiResponses() {
    }

    /** 创建批次响应。 */
    public record CreateBatchResponse(String batchCode, String status, Instant createdAt) {
    }

    /** 电芯入批响应：初始电压读数编号与入批时刻。 */
    public record IntakeResponse(String readingCode, String batchCode, String cellCode,
                                 double initialVoltage, Instant measuredAt) {
    }

    /** 终测读数响应。 */
    public record FinalReadingResponse(String readingCode, String batchCode, String cellCode,
                                       double voltage, Instant measuredAt) {
    }

    /** 交接签收响应：冻结的电芯清单、终测读数编号、交接双方与 UTC 时刻。 */
    public record HandoverResponse(String handoverCode, String batchCode, String fromParty,
                                   String toParty, Instant handedOverAt,
                                   List<FrozenCell> cells) {
    }

    /** 交接冻结的单枚电芯及其终测读数编号。 */
    public record FrozenCell(String cellCode, String finalReadingCode) {
    }
}