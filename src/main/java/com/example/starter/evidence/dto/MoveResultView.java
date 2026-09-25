package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 迁移执行结果视图：第二人确认成功后的完整迁移结果，
 * 含迁移单、不可变双人迁移记录与逐件封签核验快照。
 *
 * @param move      迁移单（状态 COMPLETED）
 * @param record    不可变双人迁移记录
 * @param snapshots 逐件封签核验快照
 */
public record MoveResultView(
        MoveView move,
        MoveRecordView record,
        List<SealSnapshotView> snapshots) {
}
