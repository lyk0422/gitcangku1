package com.example.starter.film.api;

import com.example.starter.film.domain.SegmentVersion;

import java.util.List;

/**
 * 影片查询视图：片段版次链、母版引用、交付回执。
 */
public record FilmView(String code, int plannedSegments,
                       List<SegmentVersion> segmentChain,
                       List<MasterView> masters,
                       List<ReceiptView> receipts) {
}
