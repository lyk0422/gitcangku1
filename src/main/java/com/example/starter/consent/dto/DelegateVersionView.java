package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.starter.consent.Purpose;

/**
 * 委托版本视图：委托历史中的单个版本。
 *
 * @param delegateVersion 委托版本，从 1 开始递增
 * @param purposes        规范化用途集合（去重并按用途名排序）
 * @param epochs          各用途绑定的授权代次（用途 -> 代次）
 * @param validFrom       有效期起（UTC，左闭）
 * @param validTo         有效期止（UTC，右开）
 */
public record DelegateVersionView(
        int delegateVersion,
        List<Purpose> purposes,
        Map<Purpose, Integer> epochs,
        Instant validFrom,
        Instant validTo) {
}
