package com.example.starter.blind.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 确定性随机化表生成器：以封存身份字段派生 SHA-256 种子流，
 * 将容量按每 4 条一组生成均衡处理代码（每组两个 A、两个 B）后做 Fisher-Yates 洗牌。
 * 相同身份与容量永远生成同一张表（摘要可复算），不同封存编号生成不同表；
 * 生成序列仅用于落库与摘要计算，不进入封存响应与日志。
 */
public final class RandomTableGenerator {

    /** 当前业务允许的处理代码集合。 */
    public static final Set<String> SUPPORTED_CODES = Set.of("A", "B");

    /** 每个均衡小组的序号数（两 A 两 B），容量必须为其整数倍。 */
    public static final int GROUP_SIZE = 4;

    private RandomTableGenerator() {
    }

    /**
     * 生成容量为 capacity 的均衡随机序列。
     *
     * @param seedString 种子身份（实验、区组、版本与封存编号的组合，必须唯一）
     * @param capacity   序列条数，必须为 {@value #GROUP_SIZE} 的整数倍
     * @return 不可变处理代码序列
     */
    public static List<String> generate(String seedString, int capacity) {
        if (capacity <= 0 || capacity % GROUP_SIZE != 0) {
            throw new IllegalArgumentException("容量必须为正且是 " + GROUP_SIZE + " 的整数倍");
        }
        List<String> sequence = new ArrayList<>(capacity);
        byte[] stream = seedStream(seedString, capacity * 8L);
        int offset = 0;
        for (int start = 0; start < capacity; start += GROUP_SIZE) {
            List<String> group = new ArrayList<>(List.of("A", "A", "B", "B"));
            // Fisher-Yates：随机性来自种子派生字节，确定性可复算。
            for (int i = GROUP_SIZE - 1; i > 0; i--) {
                int j = Byte.toUnsignedInt(stream[offset++]) % (i + 1);
                Collections.swap(group, i, j);
            }
            sequence.addAll(group);
        }
        return Collections.unmodifiableList(sequence);
    }

    /**
     * 规范化处理代码集合：去重、字典序排序，并校验取值范围。
     */
    public static List<String> normalizeCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new IllegalArgumentException("处理代码集合不能为空");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String code : codes) {
            if (code == null || !SUPPORTED_CODES.contains(code)) {
                throw new IllegalArgumentException("不支持的处理代码: " + code);
            }
            sorted.add(code);
        }
        return List.copyOf(sorted);
    }

    /**
     * 由种子字符串派生不重复 SHA-256 块组成的随机字节流。
     */
    private static byte[] seedStream(String seedString, long neededBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] seedBytes = seedString.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[(int) neededBytes];
            int filled = 0;
            long counter = 0;
            while (filled < neededBytes) {
                digest.update(seedBytes);
                digest.update((byte) (counter >>> 56));
                digest.update((byte) (counter >>> 48));
                digest.update((byte) (counter >>> 40));
                digest.update((byte) (counter >>> 32));
                digest.update((byte) (counter >>> 24));
                digest.update((byte) (counter >>> 16));
                digest.update((byte) (counter >>> 8));
                digest.update((byte) counter);
                byte[] block = digest.digest();
                System.arraycopy(block, 0, out, filled,
                        Math.min(block.length, (int) neededBytes - filled));
                filled += block.length;
                counter++;
            }
            return out;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
