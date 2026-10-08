package com.example.starter.domain;

import java.util.List;

/**
 * 带孔洞的简单多边形：一个外环与 0~2 个孔洞。
 *
 * <p>顶点首尾不重复，列表顺序即环的实际连接关系（末点隐式连回首点）。
 * 禁飞实体为“外环闭包内部去掉各孔洞内部”，外环边界与孔洞边界都属于禁飞实体。</p>
 *
 * @param outer 外环有序顶点（3~10 个）
 * @param holes 孔洞环有序列表（0~2 个，每个 3~8 顶点）
 */
public record PolygonWithHoles(List<Point> outer, List<List<Point>> holes) {
}
