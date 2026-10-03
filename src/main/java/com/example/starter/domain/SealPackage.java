package com.example.starter.domain;

import java.time.Instant;

/**
 * 封存包：同一层位最多装 20 件样本，交接时锁定清单与封签编号。
 *
 * @param id 自增主键
 * @param packageNo 封存包编号，全项目唯一
 * @param stratumId 所属层位，包内样本必须同属该层位
 * @param status 状态：OPEN 装包中 / HANDED_OVER 已交接 / ABNORMAL 封签异常 / SUPERSEDED 已被重新封存取代
 * @param sealNo 封签编号，交接时锁定，未交接为 null
 * @param version 版本号，每次变更递增
 * @param supersedesPackageId 重新封存时被取代的旧包 id，null 表示非重新封存包
 * @param sourceEventId 重新封存时引用的旧包异常记录 id，null 表示无
 * @param createdAt 建包时刻（UTC）
 * @param handedOverAt 交接时刻（UTC），未交接为 null
 */
public record SealPackage(long id, String packageNo, long stratumId, String status, String sealNo, int version,
        Long supersedesPackageId, Long sourceEventId, Instant createdAt, Instant handedOverAt) {
}