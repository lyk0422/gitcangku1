package com.example.starter.baggage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.example.starter.baggage.BaggageDtos.ProofNode;

/**
 * MERKLE 航段封舱清单摘要树。
 * 袋号按 UTF-8 字节无符号序排列；叶摘要为 SHA-256 作用于字节 0、四字节大端袋号字节长度
 * 和袋号 UTF-8 字节；父摘要作用于字节 1 及左右各 32 字节子摘要；奇数叶复制末项。
 * 摘要输入均为定长字节拼接，禁止分隔符拼接。空清单无根，封舱时拒绝。
 */
public final class MerkleTree {

    /** 证明中兄弟节点位于路径节点右侧（路径节点为左子）。 */
    public static final String SIDE_RIGHT = "RIGHT";
    /** 证明中兄弟节点位于路径节点左侧（路径节点为右子）。 */
    public static final String SIDE_LEFT = "LEFT";

    private MerkleTree() {
    }

    /** 袋号比较器：按 UTF-8 字节无符号序。 */
    public static final Comparator<String> BAG_TAG_ORDER =
            Comparator.comparing((String tag) -> tag.getBytes(StandardCharsets.UTF_8),
                    Arrays::compareUnsigned);

    /** 叶摘要：SHA-256(0x00 || 4 字节大端袋号字节长度 || 袋号 UTF-8 字节)。 */
    public static byte[] leafDigest(String bagTag) {
        byte[] data = bagTag.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = sha256();
        digest.update((byte) 0);
        digest.update((byte) (data.length >>> 24));
        digest.update((byte) (data.length >>> 16));
        digest.update((byte) (data.length >>> 8));
        digest.update((byte) data.length);
        digest.update(data);
        return digest.digest();
    }

    /** 父摘要：SHA-256(0x01 || 左 32 字节 || 右 32 字节)。 */
    public static byte[] parentDigest(byte[] left, byte[] right) {
        if (left.length != 32 || right.length != 32) {
            throw new IllegalArgumentException("子摘要必须为 32 字节");
        }
        MessageDigest digest = sha256();
        digest.update((byte) 1);
        digest.update(left);
        digest.update(right);
        return digest.digest();
    }

    /** 由已按 UTF-8 字节无符号序排列的袋号清单计算根摘要；空清单拒绝。 */
    public static byte[] root(List<String> sortedBagTags) {
        if (sortedBagTags.isEmpty()) {
            throw new IllegalArgumentException("空清单无根摘要");
        }
        List<byte[]> level = leaves(sortedBagTags);
        while (level.size() > 1) {
            level = parents(level);
        }
        return level.get(0);
    }

    /** 树高（证明路径长度）：叶数 1 时高 0，否则为 ceil(log2(叶数))。 */
    public static int treeHeight(int leafCount) {
        if (leafCount < 1) {
            throw new IllegalArgumentException("叶数必须为正数");
        }
        int height = 0;
        int width = 1;
        while (width < leafCount) {
            width <<= 1;
            height++;
        }
        return height;
    }

    /**
     * 生成指定叶下标的成员证明：逐层兄弟摘要及其所在侧。
     * 奇数层末项的兄弟为其自身（复制末项）。
     */
    public static List<ProofNode> proof(List<String> sortedBagTags, int leafIndex) {
        if (sortedBagTags.isEmpty()) {
            throw new IllegalArgumentException("空清单无成员证明");
        }
        if (leafIndex < 0 || leafIndex >= sortedBagTags.size()) {
            throw new IllegalArgumentException("叶下标越界: " + leafIndex);
        }
        List<byte[]> level = leaves(sortedBagTags);
        List<ProofNode> proof = new ArrayList<>();
        int index = leafIndex;
        while (level.size() > 1) {
            List<byte[]> padded = padOdd(level);
            int siblingIndex = index ^ 1;
            String side = index % 2 == 0 ? SIDE_RIGHT : SIDE_LEFT;
            proof.add(new ProofNode(side, hex(padded.get(siblingIndex))));
            level = parents(level);
            index /= 2;
        }
        return proof;
    }

    /** 摘要转小写十六进制字符串。 */
    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    /** 十六进制摘要解析，非法格式或长度返回 null（由调用方转 422）。 */
    public static byte[] parseHex(String text) {
        if (text == null || text.length() != 64) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<byte[]> leaves(List<String> sortedBagTags) {
        List<byte[]> leaves = new ArrayList<>(sortedBagTags.size());
        for (String bagTag : sortedBagTags) {
            leaves.add(leafDigest(bagTag));
        }
        return leaves;
    }

    private static List<byte[]> parents(List<byte[]> level) {
        List<byte[]> padded = padOdd(level);
        List<byte[]> parents = new ArrayList<>(padded.size() / 2);
        for (int i = 0; i < padded.size(); i += 2) {
            parents.add(parentDigest(padded.get(i), padded.get(i + 1)));
        }
        return parents;
    }

    /** 奇数个节点时复制末项凑成偶数。 */
    private static List<byte[]> padOdd(List<byte[]> level) {
        List<byte[]> padded = new ArrayList<>(level);
        if (padded.size() % 2 == 1) {
            padded.add(padded.get(padded.size() - 1));
        }
        return padded;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
