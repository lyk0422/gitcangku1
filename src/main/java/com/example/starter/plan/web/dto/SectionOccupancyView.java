package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 按区段的当前等级占用查询结果。
 *
 * @param sectionId      区段 ID
 * @param priority       登记等级 1～5；未登记为 null
 * @param effectiveLevel 参与抢占判定的实际等级（未登记按最低等级 1）
 * @param occupancies    当前已发布计划在该区段上的生效时隙（可按运营日过滤）
 */
public record SectionOccupancyView(String sectionId, Integer priority, int effectiveLevel,
                                   List<SectionSlotView> occupancies) {
}
