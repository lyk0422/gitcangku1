package com.example.starter.model;

/**
 * 引航员行记录。
 *
 * @param id                 主键
 * @param code               工号，全局唯一
 * @param name               姓名
 * @param qualificationLevel 资格等级，数值越大等级越高
 */
public record PilotRow(long id, String code, String name, int qualificationLevel) {
}