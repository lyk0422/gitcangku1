package com.example.starter.batch;

import com.example.starter.batch.dto.SpecConstraintInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 耦合公差规范定义（内存形态）：2～5 个有序无量纲特性 + 1～8 条线性不等式。
 * 每条约束语义：整数系数（-100～100，不全为零）乘特性之和不大于精确十进制上界，等号合格；
 * 评估使用 BigDecimal 精确比较，不依赖任何显示舍入。
 */
public record SpecDefinition(List<String> features, List<Constraint> constraints) {

    /**
     * 单条线性不等式：coefficients 与 features 顺序一一对应。
     */
    public record Constraint(List<Integer> coefficients, BigDecimal bound) {
    }

    /**
     * 由请求输入构建并校验定义；非法定义抛 400。
     */
    public static SpecDefinition of(List<String> features, List<SpecConstraintInput> constraints) {
        List<String> names = features.stream().map(String::trim).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw ApiException.badRequest("features 存在重复特性名");
        }
        List<Constraint> parsed = new ArrayList<>(constraints.size());
        for (SpecConstraintInput input : constraints) {
            List<Integer> coefficients = input.coefficients();
            if (coefficients == null || coefficients.size() != names.size()) {
                throw ApiException.badRequest("每条约束的系数个数必须等于特性个数: " + names.size());
            }
            boolean allZero = true;
            for (Integer coefficient : coefficients) {
                if (coefficient == null) {
                    throw ApiException.badRequest("约束系数不能为空");
                }
                if (coefficient < -100 || coefficient > 100) {
                    throw ApiException.badRequest("约束系数必须在 -100～100 之间: " + coefficient);
                }
                if (coefficient != 0) {
                    allZero = false;
                }
            }
            if (allZero) {
                throw ApiException.badRequest("约束系数不得全为零");
            }
            if (input.bound() == null) {
                throw ApiException.badRequest("约束上界不能为空");
            }
            parsed.add(new Constraint(List.copyOf(coefficients), input.bound()));
        }
        return new SpecDefinition(List.copyOf(names), List.copyOf(parsed));
    }

    /**
     * 精确评估完整向量：返回越界约束序号（从 1 开始），空表表示全部满足（PASS）。
     * 调用方保证 values 恰好覆盖全部特性。
     */
    public List<Integer> evaluate(Map<String, BigDecimal> values) {
        List<Integer> violated = new ArrayList<>();
        for (int i = 0; i < constraints.size(); i++) {
            Constraint constraint = constraints.get(i);
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 0; j < features.size(); j++) {
                sum = sum.add(BigDecimal.valueOf(constraint.coefficients().get(j))
                        .multiply(values.get(features.get(j))));
            }
            if (sum.compareTo(constraint.bound()) > 0) {
                violated.add(i + 1);
            }
        }
        return violated;
    }

    /**
     * 结构化指纹片段：特性顺序、全部系数与规范化上界；十进制同值同文，文本原样。
     */
    public List<String> canonicalParts() {
        List<String> parts = new ArrayList<>();
        parts.addAll(features);
        for (Constraint constraint : constraints) {
            for (Integer coefficient : constraint.coefficients()) {
                parts.add(coefficient.toString());
            }
            parts.add(canonicalDecimal(constraint.bound()));
        }
        return parts;
    }

    /**
     * 十进制同值规范化：去尾零后的 plain 文本；零统一为 "0"。用于指纹的同值同参语义。
     */
    public static String canonicalDecimal(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    public String featuresJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(features);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("特性序列化失败", e);
        }
    }

    public String constraintsJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(constraints);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("约束序列化失败", e);
        }
    }

    /**
     * 从存储 JSON 还原定义。
     */
    public static SpecDefinition fromJson(String featuresJson, String constraintsJson,
                                          ObjectMapper objectMapper) {
        try {
            List<String> features = objectMapper.readValue(featuresJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            List<Constraint> constraints = objectMapper.readValue(constraintsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Constraint.class));
            return new SpecDefinition(features, constraints);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范定义反序列化失败", e);
        }
    }
}
