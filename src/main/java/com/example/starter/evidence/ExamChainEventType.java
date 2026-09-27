package com.example.starter.evidence;

/**
 * 检验保管链事件类型。事件只追加、不可变。
 * RESERVED 预约占位；STARTED 开始检验（临时链记录，锁定原封签版本）；
 * COMPLETED 完成检验并重封（记录结果摘要与新封签校验）；CANCELLED 未开始预约被取消。
 */
public enum ExamChainEventType {
    RESERVED,
    STARTED,
    COMPLETED,
    CANCELLED
}
