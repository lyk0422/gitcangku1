package com.example.starter.query;

import java.time.Instant;
import java.util.List;

/**
 * 背景查询传输对象：展示样本层位、坐标格、所属包的版本与每次封签状态。
 */
public final class QueryDtos {

    private QueryDtos() {
    }

    /** 封签事件视图：eventType 为 SEALED/HANDED_OVER/SEAL_BROKEN/RESEALED，occurredAt 为 UTC 时刻。 */
    public record SealEventView(String eventType, String sealNo, String detail, Instant occurredAt) {
    }

    /** 封存包视图：version 为包版本，events 为该包全部封签事件（按发生顺序）。 */
    public record PackageView(String packageNo, String lineageNo, int version, String sealNo, String status,
            List<SealEventView> events) {
    }

    /** 样本背景视图：层位、坐标格、UTC 采集时刻与历次入包记录。 */
    public record SampleContextResponse(String sampleNo, String siteCode, String stratumNo, String gridRef,
            Instant collectedAt, String status, List<PackageView> packages) {
    }
}