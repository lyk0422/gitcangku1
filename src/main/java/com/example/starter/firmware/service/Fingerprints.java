package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

/**
 * 幂等指纹构造：将操作者、操作类型、规范化对象集合及全部结果相关字段组织为对象后，
 * 序列化为键排序的紧凑 JSON，再取 SHA-256 十六进制作为指纹。
 * 禁止用未转义分隔符直接拼接字符串，避免 ("a","b|c") 与 ("a|b","c") 碰撞；
 * 哈希定长且可承载大批量参数。
 */
@Component
public class Fingerprints {

    private final ObjectMapper objectMapper;

    public Fingerprints(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 以操作类型开头构造一个键排序的可变结构。
     */
    public TreeMap<String, Object> builder(String operation) {
        TreeMap<String, Object> structure = new TreeMap<>();
        structure.put("operation", operation);
        return structure;
    }

    /**
     * 规范化 JSON 后取 SHA-256 十六进制；结构中的集合需由调用方先规范化排序。
     */
    public String canonical(Object structure) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(structure);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
