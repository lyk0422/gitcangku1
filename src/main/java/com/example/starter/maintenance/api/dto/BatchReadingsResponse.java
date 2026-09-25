package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 工单批量登记读数结果：全部成功时返回实际入库的读数；失败时整体 422 且无读数入库。
 *
 * @param workOrderId       工单标识
 * @param equipmentId       所属设备标识
 * @param acceptedCount     入库读数条数
 * @param readings          入库读数当前值（按采样时刻升序）
 * @param equipmentVersion  操作后的设备版本号
 * @param workOrderVersion  操作后的工单版本号
 */
public record BatchReadingsResponse(
        String workOrderId,
        String equipmentId,
        int acceptedCount,
        List<ReadingResponse> readings,
        long equipmentVersion,
        long workOrderVersion) {
}
