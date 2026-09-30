package com.example.starter.film.api;

import java.util.List;

/**
 * 母版中单个片段的固定引用。
 */
public record MasterSegmentView(int segmentNo, int versionNo, String contentDigest) {
}
