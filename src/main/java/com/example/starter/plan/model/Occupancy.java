package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划内一条占用，区间左闭右开 [startUtc, endUtc)。
 *
 * <p>两种形态：{@code platformId} 为空时为仅按区段裁决的普通占用；
 * 非空时为编组到发站台占用，携带调度员登记的编组三要素，发布成功后固化
 * {@code capabilityVersion}（当时站台能力版本，能力随后升级也不改写历史）。
 *
 * @param id                   主键
 * @param planId               所属计划 id
 * @param seq                  计划内序号，保持提交顺序
 * @param trainNo              列车编号
 * @param sectionId            区段 ID
 * @param startUtc             占用开始时刻（含），UTC
 * @param endUtc               占用结束时刻（不含），UTC，必须晚于 startUtc
 * @param platformId           站台业务键，可空；非空表示站台到发占用
 * @param consistLengthMeters  编组长度，单位米；站台占用时非空且为正
 * @param powerSystem          编组供电制式标识（如 AC25KV/DC1500V）；站台占用时非空
 * @param accessibleRequired   是否需要无障碍服务；站台占用时非空，TRUE 需要
 * @param capabilityVersion    发布时固化的站台能力版本；草稿与普通占用为空
 */
public record Occupancy(long id, long planId, int seq, String trainNo, String sectionId,
                        Instant startUtc, Instant endUtc, String platformId,
                        Integer consistLengthMeters, String powerSystem,
                        Boolean accessibleRequired, Integer capabilityVersion) {

    /** 是否为编组到发站台占用。 */
    public boolean platformOccupancy() {
        return platformId != null;
    }
}
