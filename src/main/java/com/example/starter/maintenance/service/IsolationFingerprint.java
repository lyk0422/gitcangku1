package com.example.starter.maintenance.service;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 幂等指纹构建：操作者、操作类型、规范化对象集合及所有影响结果的字段，
 * 统一序列化为规范化 JSON（集合先排序、字段按固定顺序写入），
 * 不通过未转义分隔符拼接，避免集合元素包含分隔符导致指纹碰撞。
 */
public final class IsolationFingerprint {

    private IsolationFingerprint() {
    }

    /** 单个对象的指纹：字段按固定顺序写入 JSON。 */
    public static String of(ObjectMapper mapper, String operatorId, String operation, ObjectNode body) {
        ObjectNode root = mapper.createObjectNode();
        root.put("operator", operatorId);
        root.put("operation", operation);
        root.set("payload", body);
        return root.toString();
    }

    /** 对象集合的指纹：集合元素先按规范化 JSON 排序，再整体序列化。 */
    public static String ofCollection(ObjectMapper mapper, String operatorId, String operation,
                                      List<ObjectNode> items) {
        List<String> normalized = items.stream().map(ObjectNode::toString).sorted().toList();
        ArrayNode array = mapper.createArrayNode();
        normalized.forEach(array::add);
        ObjectNode root = mapper.createObjectNode();
        root.put("operator", operatorId);
        root.put("operation", operation);
        root.set("payload", array);
        return root.toString();
    }
}
