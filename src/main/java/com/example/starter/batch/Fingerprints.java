package com.example.starter.batch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 幂等指纹工具：对操作者、操作类型与全部影响结果的字段做规范化 SHA-256 摘要。
 *
 * <p>每个标量字段以「4 字节小端长度 + UTF-8 字节」写入，集合先写元素个数再逐元素写入，
 * 不依赖任何分隔符拼接，因此元素中包含 NUL、竖线等字符也不会产生碰撞。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 对标量字段序列计算指纹；字段顺序本身参与摘要，调用方须按稳定（规范化）顺序传入。
     */
    public static String sha256(String... parts) {
        return sha256(List.of(parts), List.of());
    }

    /**
     * 计算包含对象集合的规范化指纹。
     *
     * @param scalars     标量字段（操作类型、操作者、路径参数、单值业务字段等），按稳定顺序
     * @param collections 规范化对象集合；每个扁平字符串列表先写元素数再逐元素长度前缀写入
     */
    public static String sha256(List<String> scalars, List<List<String>> collections) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            for (String scalar : scalars) {
                writeText(out, scalar == null ? "" : scalar);
            }
            out.writeInt(collections.size());
            for (List<String> collection : collections) {
                out.writeInt(collection.size());
                for (String element : collection) {
                    writeText(out, element == null ? "" : element);
                }
            }
            out.flush();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(buffer.toByteArray());
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 指纹计算失败", e);
        }
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
