package com.example.starter.archival.web.dto;

/**
 * 入组结果。replayed=true 表示同 requestId 同参重放的首次结果。
 *
 * @param batchNo     批次号
 * @param accessionNo 馆藏号
 * @param reactivated 是否为归还后重新入组（复用原在组行）
 * @param replayed    是否为幂等重放
 */
public record EnrollResult(String batchNo, String accessionNo,
                           boolean reactivated, boolean replayed) {
}
