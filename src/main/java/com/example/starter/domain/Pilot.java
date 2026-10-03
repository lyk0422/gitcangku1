package com.example.starter.domain;

/**
 * 引航员。
 *
 * @param id                 主键
 * @param pilotCode          工号，唯一
 * @param pilotName          姓名
 * @param qualificationGrade 资格等级，数值越大资格越高，须不低于船舶要求等级
 * @param active             是否在岗
 */
public record Pilot(long id, String pilotCode, String pilotName, int qualificationGrade, boolean active) {
}