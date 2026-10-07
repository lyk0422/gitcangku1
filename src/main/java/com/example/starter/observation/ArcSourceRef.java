package com.example.starter.observation;

/**
 * 裁定引用：一条圆弧观测记录的当前版本，字段身份（observationId）与源版本（version）不可省。
 *
 * @param observationId 引用的圆弧观测记录唯一标识
 * @param version       引用的源版本号，裁定时必须等于该记录当前版本，否则 409
 */
public record ArcSourceRef(String observationId, Integer version) {
}
