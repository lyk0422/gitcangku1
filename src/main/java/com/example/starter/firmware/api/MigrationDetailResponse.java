package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.MigrationItem;
import com.example.starter.firmware.domain.MigrationOrder;
import com.example.starter.firmware.domain.ReceiptHistory;

import java.util.List;

/**
 * 迁移单只读查询结果：迁移前后队列、每设备指令代次，以及迁移提交后到达的旧代次迟到（LATE）回执证据。
 */
public record MigrationDetailResponse(long migrationId, String migrationKey, long releaseId, String status,
                                      int deviceCount, String committedAt,
                                      List<ItemView> items, List<LateReceiptView> lateReceipts) {

    /**
     * 设备迁移前后队列与指令代次。
     */
    public record ItemView(String deviceId, long fromCohortId, long toCohortId,
                           int expectedAssignmentVersion, int oldGeneration, int newGeneration,
                           Long supersededCommandId, long newCommandId) {

        static ItemView of(MigrationItem item) {
            return new ItemView(item.deviceId(), item.fromCohortId(), item.toCohortId(),
                    item.expectedAssignmentVersion(), item.oldGeneration(), item.newGeneration(),
                    item.oldCommandId(), item.newCommandId());
        }
    }

    /**
     * 迁移提交后到达的旧代次回执：仅保存为历史 LATE，不改变目标队列成功数、失败率或暂停状态。
     */
    public record LateReceiptView(String deviceId, long commandId, long cohortId, int generation,
                                  String result, boolean settled, boolean duplicate, String receiptAt) {

        static LateReceiptView of(ReceiptHistory history) {
            return new LateReceiptView(history.deviceId(), history.commandId(), history.cohortId(),
                    history.generation(), history.result(), history.settled(), history.duplicate(),
                    history.receiptAt());
        }
    }

    public static MigrationDetailResponse of(MigrationOrder order, List<MigrationItem> items,
                                             List<ReceiptHistory> lateHistories) {
        List<ItemView> itemViews = items.stream().map(ItemView::of).toList();
        List<LateReceiptView> lateViews = lateHistories.stream().map(LateReceiptView::of).toList();
        return new MigrationDetailResponse(order.id(), order.migrationKey(), order.releaseId(),
                order.status().name(), order.deviceCount(), order.committedAt(), itemViews, lateViews);
    }
}
