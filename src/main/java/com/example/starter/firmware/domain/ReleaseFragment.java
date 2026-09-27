package com.example.starter.firmware.domain;

/**
 * 发布分片登记项（持久化行）。按 seqNo 升序即为规范排序。
 *
 * @param releaseId 所属发布单ID
 * @param seqNo    分片序号，从0开始连续
 * @param digest   分片摘要，64位小写十六进制 SHA-256
 */
public record ReleaseFragment(long releaseId, int seqNo, String digest) {
}
