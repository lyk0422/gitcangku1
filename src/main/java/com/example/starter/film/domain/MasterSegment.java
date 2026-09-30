package com.example.starter.film.domain;

/**
 * 母版在审核通过瞬间固定下来的单个片段引用；版次与摘要成对冻结，不读取后来更新的工作版。
 *
 * @param masterId      所属母版 ID
 * @param segmentNo     片段序号
 * @param versionNo     固定的片段版次
 * @param contentDigest 固定的片段内容摘要
 */
public record MasterSegment(long masterId, int segmentNo, int versionNo, String contentDigest) {
}
