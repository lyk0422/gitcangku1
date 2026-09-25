package com.example.starter.blind.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 中心盲码序列的区组排列生成器：协议比例 ratioA:ratioB 和为 100，
 * 每个 100 码区组内恰好含 ratioA 个 A、ratioB 个 B，并随机洗牌；
 * 最后一个区组按剩余容量截断。区组内容（处理映射）仅落库，不输出日志。
 */
@Component
public class SequenceBlockGenerator {

    private final SecureRandom random = new SecureRandom();

    /** 比例规范化（除以最大公约数），用于 protocolKey 指纹的稳定表达。 */
    public static int[] normalizedRatio(int ratioA, int ratioB) {
        int gcd = gcd(ratioA, ratioB);
        return new int[]{ratioA / gcd, ratioB / gcd};
    }

    private static int gcd(int x, int y) {
        return y == 0 ? x : gcd(y, x % y);
    }

    /** 生成 capacity 个处理代码：按每 100 个一区组、区组内随机排列。 */
    public List<String> generateTreatments(int ratioA, int ratioB, int capacity) {
        List<String> result = new ArrayList<>(capacity);
        int remaining = capacity;
        while (remaining > 0) {
            int blockSize = Math.min(100, remaining);
            List<String> block = newBlock(ratioA, ratioB, blockSize);
            result.addAll(block);
            remaining -= blockSize;
        }
        return result;
    }

    /**
     * 生成一个区组：100 个位置中按比例放 A/B 后整体洗牌；
     * 截断区组（blockSize&lt;100）从完整洗牌区组前缀截取，前缀比例随洗牌而随机。
     */
    private List<String> newBlock(int ratioA, int ratioB, int blockSize) {
        List<String> full = new ArrayList<>(100);
        for (int i = 0; i < ratioA; i++) {
            full.add("A");
        }
        for (int i = 0; i < ratioB; i++) {
            full.add("B");
        }
        Collections.shuffle(full, random);
        return new ArrayList<>(full.subList(0, blockSize));
    }
}
