package com.example.starter.container.dto;

/**
 * 容器复核结果视图。
 *
 * @param container       复核后容器视图
 * @param custodianId     本次复核保管人
 * @param distinctReviews 当前容器累计的不同复核保管人数（达到 2 时容器恢复 SEALED 并解除借出/迁移阻断）
 * @param restored        本次复核是否使容器恢复 SEALED（第二名不同保管人）
 */
public record ContainerReviewResultView(
        ContainerView container,
        String custodianId,
        int distinctReviews,
        boolean restored) {
}
