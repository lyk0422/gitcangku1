package com.example.starter.maintenance.service;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 结构化幂等指纹：以 JSON 承载操作、操作者、资源、全部参数与期望版本，
 * 字段名与类型化取值避免字符串拼接的分隔符碰撞。
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成写操作指纹。
     *
     * @param operation       操作类型
     * @param operator        操作者标识
     * @param resource        资源标识（如部件编号）
     * @param params          全部业务参数（不含 requestId）
     * @param expectedVersion 期望版本号；无版本语义的写操作传 null
     */
    public String of(String operation, String operator, String resource,
                     Map<String, ?> params, Long expectedVersion) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operation", operation);
        node.put("operator", operator);
        node.put("resource", resource);
        if (expectedVersion != null) {
            node.put("expectedVersion", expectedVersion);
        }
        node.set("params", objectMapper.valueToTree(params));
        return node.toString();
    }
}
