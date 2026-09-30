package com.example.starter.film.service;

import com.example.starter.film.model.Master;
import com.example.starter.film.model.ReviewRequest;

/**
 * 审核通过结果：最终审核状态与生成的不可变母版。
 */
public record ApproveOutcome(
        ReviewRequest review,
        Master master
) {
}
