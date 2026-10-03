package com.example.starter.domain;

import java.time.Instant;

/** 数据库行记录载体；时刻均为 UTC，电压单位伏特(V)。 */
public final class Rows {

    private Rows() {
    }

    /** 放电批次行。 */
    public record BatchRow(String batchCode, BatchStatus status, Instant createdAt) {
    }

    /** 电芯行；batchCode 为空表示不在任何进行中批次。 */
    public record CellRow(String cellCode, CellStatus status, String batchCode, Instant createdAt) {
    }

    /** 放电读数行；valid=false 表示已作废但历史保留。 */
    public record ReadingRow(String readingCode, String cellCode, String batchCode,
                             ReadingType readingType, double voltage, Instant measuredAt,
                             boolean valid, String requestId, Instant createdAt) {
    }

    /** 电芯隔离记录行。 */
    public record IsolationRow(long id, String cellCode, String batchCode, String reason,
                               Instant isolatedAt) {
    }

    /** 交接单行。 */
    public record HandoverRow(String handoverCode, String batchCode, String fromParty,
                              String toParty, Instant handedOverAt, String requestId) {
    }

    /** 交接冻结明细行：交接时采用的终测读数编号。 */
    public record HandoverCellRow(String handoverCode, String cellCode, String finalReadingCode) {
    }

    /** 幂等请求键行。 */
    public record IdempotencyRow(String requestId, String endpoint, String paramsHash,
                                 int responseStatus, String responseBody, Instant createdAt) {
    }
}