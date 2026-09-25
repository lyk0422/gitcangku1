package com.example.starter.plan.web.dto;

/**
 * 抢占涉及区段视图。
 *
 * @param sectionId    涉及区段 ID
 * @param sectionLevel 该区段在抢占时刻的登记等级
 */
public record PreemptionSectionView(String sectionId, int sectionLevel) {
}
