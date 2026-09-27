package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 库位库存视图：库位信息 + 当前位于该库位的全部证物。
 *
 * @param location 库位视图
 * @param items    当前库位等于该库位的证物列表（按证物键排序）
 */
public record LocationInventoryView(
        LocationView location,
        List<EvidenceView> items) {
}
