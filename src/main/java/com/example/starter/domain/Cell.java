package com.example.starter.domain;

/**
 * 空域网格单元（固定 20 米方格）。坐标对 20 米做 floorDiv 得到单元索引，
 * 负坐标也能得到连续无重叠的网格。
 *
 * @param x 单元 X 索引
 * @param y 单元 Y 索引
 */
public record Cell(int x, int y) {
}
