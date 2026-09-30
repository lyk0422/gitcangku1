package com.example.starter.salinity.po;

import java.time.Instant;

/**
 * 封存后追加的勘误说明，仅追加不修改；原冻结剖面继续可查。
 *
 * @param id            自增主键
 * @param voyageCode    航次编号，非空
 * @param note          勘误正文，非空
 * @param refSealedUtc  所引用的原封存 UTC 时刻，非空
 * @param createdUtc    勘误创建 UTC 时刻，非空
 */
public record ProfileErratum(
        Long id,
        String voyageCode,
        String note,
        Instant refSealedUtc,
        Instant createdUtc
) {
}
