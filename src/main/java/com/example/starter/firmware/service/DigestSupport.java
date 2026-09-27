package com.example.starter.firmware.service;

import com.example.starter.firmware.api.FragmentInput;
import com.example.starter.firmware.domain.ReleaseFragment;
import com.example.starter.firmware.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分片摘要规则（固定格式）：
 * 1. 每个分片摘要为 64 位小写十六进制 SHA-256；
 * 2. 分片序号必须从 0 开始连续，登记时按序号升序规范排序；
 * 3. 聚合摘要 = 将各分片摘要按序号升序以换行符 "\n" 拼接成一行一个的文本，再求 SHA-256；
 * 4. 登记的完整包摘要必须与聚合摘要一致。
 */
public final class DigestSupport {

    public static final String DIGEST_REGEX = "[0-9a-f]{64}";

    private DigestSupport() {
    }

    public static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }

    /**
     * 按规范排序并校验登记分片：重复序号 422；序号缺口（必须为 0..n-1）422。
     * 返回按序号升序、可直接落库的分片列表。
     */
    public static List<ReleaseFragment> normalizeInputs(List<FragmentInput> inputs) {
        Map<Integer, String> bySeq = new LinkedHashMap<>();
        for (FragmentInput input : inputs) {
            if (bySeq.put(input.seqNo(), input.digest()) != null) {
                throw ApiException.unprocessable("DUPLICATE_SEQ",
                        "分片序号重复: " + input.seqNo());
            }
        }
        List<Integer> seqs = new ArrayList<>(bySeq.keySet());
        seqs.sort(Integer::compareTo);
        if (seqs.get(0) != 0) {
            throw ApiException.unprocessable("SEQ_GAP",
                    "分片序号必须从0开始，首个实际序号: " + seqs.get(0));
        }
        List<ReleaseFragment> normalized = new ArrayList<>(seqs.size());
        int expected = 0;
        for (int seq : seqs) {
            if (seq != expected) {
                throw ApiException.unprocessable("SEQ_GAP",
                        "分片序号缺口: 期望 " + expected + "，实际 " + seq);
            }
            normalized.add(new ReleaseFragment(0, seq, bySeq.get(seq)));
            expected++;
        }
        return normalized;
    }

    /**
     * 计算按序号升序的分片摘要聚合值：一行一个摘要，以 "\n" 分隔，末尾不追加换行。
     */
    public static String aggregate(List<? extends String> digestsOrdered) {
        return sha256Hex(String.join("\n", digestsOrdered));
    }

    /**
     * 校验完整包摘要与聚合摘要一致，不一致 422。
     */
    public static void verifyPackageDigest(List<ReleaseFragment> normalizedFragments, String packageDigest) {
        List<String> digests = normalizedFragments.stream().map(ReleaseFragment::digest).toList();
        String actual = aggregate(digests);
        if (!actual.equals(packageDigest)) {
            throw ApiException.unprocessable("PACKAGE_DIGEST_MISMATCH",
                    "聚合摘要与完整包摘要不匹配，实际聚合: " + actual);
        }
    }
}
