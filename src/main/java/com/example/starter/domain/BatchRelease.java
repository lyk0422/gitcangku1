package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批次放行事实。只允许凭批次当前有效证书放行一次，
 * 行内容（证书编号与两次检测编号、比例）为放行时刻的证书快照，之后不作回滚。
 *
 * @param id              数据库主键
 * @param batchId         所属批次主键，全库唯一（一批次只放行一次）
 * @param certificateId   放行所依据证书的主键
 * @param certificateNo   放行所依据证书编号快照
 * @param firstTestNo     证书第一次检测编号快照
 * @param firstRatio      证书第一次检测发芽率快照
 * @param secondTestNo    证书第二次检测编号快照
 * @param secondRatio     证书第二次检测发芽率快照
 * @param requestId       放行请求的幂等键
 * @param releasedAt      放行时刻（Asia/Shanghai）
 * @param createdAt       入库时刻（Asia/Shanghai）
 */
public record BatchRelease(
        Long id,
        Long batchId,
        Long certificateId,
        String certificateNo,
        String firstTestNo,
        BigDecimal firstRatio,
        String secondTestNo,
        BigDecimal secondRatio,
        String requestId,
        LocalDateTime releasedAt,
        LocalDateTime createdAt
) {
}
