package com.example.starter.workblock.model;

/**
 * 施工单取消不可变记录。取消成功时追加，之后不修改、不删除。
 *
 * @param id          主键
 * @param workBlockId 被取消施工单 id，关联 rail_work_block.id，全表唯一
 * @param workKey     被取消施工单业务键快照
 * @param version     取消时的施工单版本快照
 * @param operator    取消操作者标识
 * @param cancelledAt 取消时刻，UTC 毫秒
 */
public record WorkBlockCancellation(long id, long workBlockId, String workKey, int version,
                                    String operator, long cancelledAt) {
}
