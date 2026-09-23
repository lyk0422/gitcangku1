package com.example.starter.restitution.dto;

/**
 * 冻结的藏品归属视图。
 *
 * @param itemNo    藏品编号
 * @param claimKey  中选主张键
 * @param applicant 中选主张申请人（藏品归还对象）
 */
public record FrozenItemView(String itemNo, String claimKey, String applicant) {
}
