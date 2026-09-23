package com.example.starter.calibration.model;

/**
 * 失效闭包快照项。创建失效单时持久化，激活时在同一事务内重算并整体比对；
 * 任一项不一致即整单 409，不允许部分冻结。
 *
 * @param itemType  快照项类型：STANDARD 标准器版本 / MEASUREMENT 测量记录
 * @param refId     引用 ID（标准器版本 ID 或测量记录 ID）
 * @param refStatus 快照时的状态（VALID/INVALID 或 PENDING/RELEASED/BLOCKED/REVIEW_REQUIRED）
 */
public record SnapshotItem(
        String itemType,
        long refId,
        String refStatus) implements Comparable<SnapshotItem> {

    /** 标准器版本快照项类型。 */
    public static final String TYPE_STANDARD = "STANDARD";

    /** 测量记录快照项类型。 */
    public static final String TYPE_MEASUREMENT = "MEASUREMENT";

    @Override
    public int compareTo(SnapshotItem other) {
        int byType = itemType.compareTo(other.itemType);
        if (byType != 0) {
            return byType;
        }
        return Long.compare(refId, other.refId);
    }
}
