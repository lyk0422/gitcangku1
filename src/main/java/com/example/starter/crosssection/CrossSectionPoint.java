package com.example.starter.crosssection;

import java.util.List;

/**
 * 断面地形点：横向坐标 x 与地面高程 z，单位均为整数毫米。
 *
 * @param x 横向坐标（毫米）
 * @param z 地面高程（毫米）
 */
public record CrossSectionPoint(int x, int z) {
}
