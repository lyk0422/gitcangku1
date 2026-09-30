package com.example.starter.film.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹与母版快照定格工具。纯函数，便于单测。
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * 片段提交参数指纹：影片、序号、摘要、基于版次全部一致才算同参。
     */
    public static String segmentSubmit(long filmId, int seq, String digest, int baseVersion) {
        return sha256("SEGMENT_SUBMIT|" + filmId + "|" + seq + "|" + digest + "|" + baseVersion);
    }

    /**
     * 审核请求参数指纹。
     */
    public static String reviewCreate(long filmId) {
        return sha256("REVIEW_CREATE|" + filmId);
    }

    /**
     * 审核通过请求参数指纹（同一审核的通过操作幂等）。
     */
    public static String reviewApprove(long filmId, long reviewId) {
        return sha256("REVIEW_APPROVE|" + filmId + "|" + reviewId);
    }

    /**
     * 撤销审核请求参数指纹。
     */
    public static String reviewRevoke(long filmId, long reviewId) {
        return sha256("REVIEW_REVOKE|" + filmId + "|" + reviewId);
    }

    /**
     * 交付请求参数指纹。
     */
    public static String delivery(long filmId, String masterNo) {
        return sha256("DELIVERY|" + filmId + "|" + masterNo);
    }

    /**
     * 将母版片段快照按序号定格为稳定字符串：{@code 序号:版次:摘要}，以 {@code |} 拼接。
     * 输入必须已按序号升序。
     */
    public static String snapshot(Iterable<? extends SnapshotLine> lines) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (SnapshotLine line : lines) {
            if (!first) {
                sb.append('|');
            }
            sb.append(line.seq()).append(':').append(line.versionNo()).append(':').append(line.digest());
            first = false;
        }
        return sb.toString();
    }

    /**
     * 快照行最小结构，母版与基线明细都可直接传入。
     */
    public interface SnapshotLine {
        int seq();

        int versionNo();

        String digest();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
