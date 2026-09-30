package com.example.starter.film.api;

import com.example.starter.film.domain.SegmentVersion;

/**
 * 片段提交结果。
 *
 * @param segmentNo      片段序号
 * @param versionNo      本次生成的版次
 * @param contentDigest  内容摘要
 * @param basedOnVersion 基于的版次；首版为 null
 * @param requestId      幂等请求号
 * @param replayed       true 表示这是同一 requestId 的重复请求，返回的是原有版次而非新提交
 */
public record SegmentSubmitResponse(int segmentNo, int versionNo, String contentDigest,
                                    Integer basedOnVersion, String requestId, boolean replayed) {

    public static SegmentSubmitResponse from(SegmentVersion v, boolean replayed) {
        return new SegmentSubmitResponse(v.segmentNo(), v.versionNo(), v.contentDigest(),
                v.basedOnVersion(), v.requestId(), replayed);
    }
}
