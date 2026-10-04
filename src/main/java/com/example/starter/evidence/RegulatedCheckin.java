package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 签到记录实体，对应 regulated_checkin 表。记录只追加、不可变，异常签到同样留痕。
 *
 * @param id            主键
 * @param transferKey   关联受监管交接业务键
 * @param checkpointNo  签到检查点号
 * @param transporterId 提交签到的运送人
 * @param sealNo        签到时提交的封条编号
 * @param arrivedAt     到达时刻（UTC）
 * @param createdAt     记录写入时间（UTC）
 */
public record RegulatedCheckin(
        Long id,
        String transferKey,
        int checkpointNo,
        String transporterId,
        String sealNo,
        LocalDateTime arrivedAt,
        LocalDateTime createdAt) {
}