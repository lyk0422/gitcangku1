package com.example.starter.evidence;

/**
 * 检验保管链事件类型，只追加、不可变。
 * RESERVED 创建预约；STARTED 开始检验（证物离开库房的临时链记录）；
 * COMPLETED 完成检验（含结果摘要与新封签版本）；CANCELLED 取消未开始预约。
 */
public enum ExaminationEventType {
    RESERVED,
    STARTED,
    COMPLETED,
    CANCELLED
}
