package com.example.starter.plan.model;

/**
 * 停靠站台主记录。
 *
 * @param id              主键
 * @param platformCode    站台业务代码，全局唯一
 * @param effectiveLength 站台当前有效长度，单位米，必须为正
 * @param version         站台版本，长度调整成功一次加一
 */
public record Platform(long id, String platformCode, int effectiveLength, int version) {
}
