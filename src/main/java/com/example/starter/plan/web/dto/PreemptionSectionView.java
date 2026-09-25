package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 抢占记录涉及的区段明细视图。
 *
 * @param sectionId    涉及区段 ID
 * @param sectionLevel 该区段登记等级（固化，未登记为 1）
 * @param startUtc     被抢占时隙开始（含），UTC
 * @param endUtc       被抢占时隙结束（不含），UTC
 */
public record PreemptionSectionView(String sectionId, int sectionLevel,
                                    Instant startUtc, Instant endUtc) {
}
