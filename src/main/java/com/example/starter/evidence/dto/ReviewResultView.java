package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 双人复核提交结果视图：容器最新状态、本次 FAIL 巡检下全部复核记录，
 * 以及本次提交是否促成容器恢复（第二名不同保管人提交时 restored=true）。
 *
 * @param container     容器最新视图
 * @param reviews       该容器全部复核记录（按发生顺序）
 * @param restored      本次提交是否使容器恢复 SEALED
 * @param reviewerCount 本次 FAIL 巡检已有不同复核保管人数量
 */
public record ReviewResultView(
        ContainerView container,
        List<ReviewView> reviews,
        boolean restored,
        int reviewerCount) {
}
