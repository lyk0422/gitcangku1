package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 库位库存视图：库位信息 + 当前在库（location_code 等于该库位）的全部证物，按证物键排序。
 *
 * @param location 库位视图
 * @param items    当前库位上的证物视图
 */
public record LocationStockView(
        LocationView location,
        List<EvidenceView> items) {
}
