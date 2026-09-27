package com.example.starter.blind.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 随机化表序列生成器：处理代码集合固定为 {A, B}，容量必须为偶数，
 * 生成的序列各占一半（区组内均衡），经 Fisher-Yates 洗牌；
 * v1 与扩容后继版本均通过本组件生成，具体序列只落库、不进入普通响应与日志。
 */
@Component
public class RandomTableGenerator {

    /** 处理代码集合，字典序升序，封存快照与版本链均以此为准。 */
    public static final List<String> TREATMENT_CODES = List.of("A", "B");

    private final SecureRandom random = new SecureRandom();

    /**
     * 生成容量为 capacity 的均衡随机序列。
     *
     * @param capacity 区组容量（序号总数），必须为正偶数
     * @return 序号 1..capacity 对应的处理代码，A/B 各占一半
     */
    public List<String> generateBalanced(int capacity) {
        if (capacity <= 0 || capacity % 2 != 0) {
            throw new IllegalArgumentException("区组容量必须为正偶数，实际要求: " + capacity);
        }
        List<String> sequence = new ArrayList<>(capacity);
        for (int i = 0; i < capacity / 2; i++) {
            sequence.add("A");
        }
        for (int i = 0; i < capacity / 2; i++) {
            sequence.add("B");
        }
        for (int i = sequence.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = sequence.get(i);
            sequence.set(i, sequence.get(j));
            sequence.set(j, tmp);
        }
        return sequence;
    }

    /** 处理代码集合的逗号分隔快照（字典序升序），如 A,B。 */
    public String treatmentCodesSnapshot() {
        return String.join(",", TREATMENT_CODES);
    }
}
