package com.example.starter.incident;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * 资质代码集合的归一化编解码：资质集合换序视为同参。
 * 存储与指纹统一使用去重后按字典序排序、英文逗号连接的字符串；空集合存为空串。
 */
public final class CredentialCodec {

    private CredentialCodec() {
    }

    /**
     * 归一化资质集合：去空白、去重、按字典序排序。null 视为空集合。
     */
    public static List<String> normalize(List<String> credentials) {
        if (credentials == null) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String credential : credentials) {
            if (credential != null && !credential.isBlank()) {
                sorted.add(credential.strip());
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * 编码为存储字符串：字典序逗号连接，空集合为空串。
     */
    public static String encode(List<String> credentials) {
        return String.join(",", normalize(credentials));
    }

    /**
     * 解码存储字符串；空串/null 为空集合。
     */
    public static List<String> decode(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return List.copyOf(Arrays.stream(stored.split(",", -1))
                .filter(s -> !s.isEmpty())
                .toList());
    }
}
