package com.example.starter.baggage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.example.starter.baggage.BaggageDtos.ProofNode;

/**
 * 新类型航段封舱清单的 Merkle 摘要与成员证明。
 * 袋号按 UTF-8 字节无符号顺序排序；叶摘要为 SHA-256(0x00 || 四字节大端袋号字节长度 || 袋号字节)，
 * 父摘要为 SHA-256(0x01 || 左 32 字节摘要 || 右 32 字节摘要)，奇数节点复制末项，空清单拒绝。
 * 摘要输入均为定长二进制拼接，禁止分隔符拼接。
 */
public final class MerkleTree {

    /** 兄弟节点方向：LEFT 表示兄弟在左（当前节点为右子），RIGHT 表示兄弟在右。 */
    public static final String SIBLING_LEFT = "LEFT";
    public static final String SIBLING_RIGHT = "RIGHT";

    /** 袋号排序：按 UTF-8 字节无符号顺序。 */
    public static final Comparator<String> BAG_TAG_ORDER =
            Comparator.comparing((String tag) -> tag.getBytes(StandardCharsets.UTF_8),
                    MerkleTree::compareBytesUnsigned);

    private MerkleTree() {
    }

    /** 叶摘要：SHA-256(0x00 || 四字节大端袋号字节长度 || 袋号 UTF-8 字节)。 */
    public static byte[] leafHash(String bagTag) {
        byte[] tag = bagTag.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = sha256();
        digest.update((byte) 0x00);
        digest.update((byte) (tag.length >>> 24));
        digest.update((byte) (tag.length >>> 16));
        digest.update((byte) (tag.length >>> 8));
        digest.update((byte) tag.length);
        digest.update(tag);
        return digest.digest();
    }

    /** 父摘要：SHA-256(0x01 || 左 32 字节摘要 || 右 32 字节摘要)。 */
    public static byte[] parentHash(byte[] left, byte[] right) {
        MessageDigest digest = sha256();
        digest.update((byte) 0x01);
        digest.update(left);
        digest.update(right);
        return digest.digest();
    }

    /** 由已按袋号字节序排序的清单计算根摘要；空清单拒绝。 */
    public static byte[] root(List<String> sortedBagTags) {
        if (sortedBagTags.isEmpty()) {
            throw new IllegalArgumentException("空清单无法计算清单根");
        }
        List<byte[]> level = sortedBagTags.stream().map(MerkleTree::leafHash).toList();
        while (level.size() > 1) {
            level = nextLevel(level);
        }
        return level.get(0);
    }

    /** 叶数对应的证明路径长度（树深）：1 个叶为 0，否则为 ceil(log2(叶数))。 */
    public static int depth(int leafCount) {
        if (leafCount < 1) {
            throw new IllegalArgumentException("叶数必须为正: " + leafCount);
        }
        int depth = 0;
        int capacity = 1;
        while (capacity < leafCount) {
            capacity <<= 1;
            depth++;
        }
        return depth;
    }

    /**
     * 生成指定叶下标的成员证明：逐层兄弟摘要及其方向。
     * 奇数层末节点的兄弟为其自身（复制末项）。
     */
    public static List<ProofNode> proof(List<String> sortedBagTags, int leafIndex) {
        if (leafIndex < 0 || leafIndex >= sortedBagTags.size()) {
            throw new IllegalArgumentException("叶下标越界: " + leafIndex);
        }
        List<byte[]> level = sortedBagTags.stream().map(MerkleTree::leafHash).toList();
        List<ProofNode> nodes = new ArrayList<>();
        int index = leafIndex;
        while (level.size() > 1) {
            int siblingIndex = index % 2 == 0 ? index + 1 : index - 1;
            String position = index % 2 == 0 ? SIBLING_RIGHT : SIBLING_LEFT;
            byte[] sibling = siblingIndex < level.size() ? level.get(siblingIndex) : level.get(index);
            nodes.add(new ProofNode(position, hex(sibling)));
            level = nextLevel(level);
            index >>= 1;
        }
        return nodes;
    }

    /**
     * 核验成员证明：路径长度须与叶数对应，逐层方向须与叶下标奇偶一致，
     * 由袋号叶摘要沿证明折叠后的根须等于期望根。任一步不符返回 false。
     */
    public static boolean verify(String bagTag, int leafIndex, int leafCount,
                                 List<ProofNode> proof, byte[] expectedRoot) {
        if (leafIndex < 0 || leafIndex >= leafCount || proof.size() != depth(leafCount)) {
            return false;
        }
        byte[] hash = leafHash(bagTag);
        int index = leafIndex;
        for (ProofNode node : proof) {
            byte[] sibling;
            try {
                sibling = unhex(node.hash());
            } catch (IllegalArgumentException ex) {
                return false;
            }
            if (sibling.length != 32) {
                return false;
            }
            String expectedPosition = index % 2 == 0 ? SIBLING_RIGHT : SIBLING_LEFT;
            if (!expectedPosition.equals(node.position())) {
                return false;
            }
            hash = SIBLING_LEFT.equals(node.position())
                    ? parentHash(sibling, hash) : parentHash(hash, sibling);
            index >>= 1;
        }
        return MessageDigest.isEqual(hash, expectedRoot);
    }

    /** 十六进制编码（小写）。 */
    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    /** 十六进制解码，非法输入抛 {@link IllegalArgumentException}。 */
    public static byte[] unhex(String text) {
        return HexFormat.of().parseHex(text);
    }

    private static List<byte[]> nextLevel(List<byte[]> level) {
        List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
        for (int i = 0; i < level.size(); i += 2) {
            byte[] left = level.get(i);
            byte[] right = i + 1 < level.size() ? level.get(i + 1) : left;
            next.add(parentHash(left, right));
        }
        return next;
    }

    private static int compareBytesUnsigned(byte[] left, byte[] right) {
        int common = Math.min(left.length, right.length);
        for (int i = 0; i < common; i++) {
            int diff = (left[i] & 0xFF) - (right[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return left.length - right.length;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
