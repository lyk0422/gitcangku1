package com.example.starter.domain;

import java.util.List;

/**
 * 带孔洞多边形的环合法性校验。
 *
 * <p>规则（非法时抛 {@link IllegalArgumentException}，由服务层转为 400）：</p>
 * <ul>
 *   <li>外环 3~10 个顶点，孔洞 0~2 个、每个 3~8 个顶点；首尾不重复；</li>
 *   <li>各环无重复顶点、无零长度边；</li>
 *   <li>各环无自交（非相邻边不得相交或共线重叠）；</li>
 *   <li>孔洞整体严格位于外环内部：顶点不在外环边上、孔边不与外环边相交；</li>
 *   <li>孔洞之间互不相交、不接触、不嵌套。</li>
 * </ul>
 */
public final class PolygonValidator {

    public static final int OUTER_MIN = 3;
    public static final int OUTER_MAX = 10;
    public static final int HOLES_MAX_COUNT = 2;
    public static final int HOLE_MIN = 3;
    public static final int HOLE_MAX = 8;

    private PolygonValidator() {
    }

    /** 校验外环与全部孔洞；非法参数抛出带中文说明的异常。 */
    public static void validate(List<Point> outer, List<List<Point>> holes) {
        if (outer == null || outer.size() < OUTER_MIN || outer.size() > OUTER_MAX) {
            throw new IllegalArgumentException(
                    "外环顶点数必须在 " + OUTER_MIN + "~" + OUTER_MAX + " 之间");
        }
        if (holes == null || holes.size() > HOLES_MAX_COUNT) {
            throw new IllegalArgumentException(
                    "孔洞数量最多为 " + HOLES_MAX_COUNT + " 个");
        }
        validateRing(outer, "外环", OUTER_MIN, OUTER_MAX);
        for (int h = 0; h < holes.size(); h++) {
            validateRing(holes.get(h), "孔洞" + (h + 1), HOLE_MIN, HOLE_MAX);
        }
        // 孔洞严格在外环内部（顶点不贴边、边不穿边）
        for (int h = 0; h < holes.size(); h++) {
            List<Point> hole = holes.get(h);
            for (Point p : hole) {
                if (!PolygonGeometry.pointStrictlyInsideRing(p, outer)) {
                    throw new IllegalArgumentException(
                            "孔洞" + (h + 1) + "必须严格位于外环内部，顶点 ("
                                    + p.x() + "," + p.y() + ") 落在外环外或外环边上");
                }
            }
            if (ringsIntersect(hole, outer)) {
                throw new IllegalArgumentException("孔洞" + (h + 1) + "的边与外环边相交");
            }
        }
        // 孔洞两两之间：边不相交、不接触，且互不包含
        for (int i = 0; i < holes.size(); i++) {
            for (int j = i + 1; j < holes.size(); j++) {
                List<Point> a = holes.get(i);
                List<Point> b = holes.get(j);
                if (ringsIntersect(a, b)) {
                    throw new IllegalArgumentException(
                            "孔洞" + (i + 1) + "与孔洞" + (j + 1) + "相交或接触");
                }
                if (PolygonGeometry.pointStrictlyInsideRing(a.get(0), b)
                        || PolygonGeometry.pointStrictlyInsideRing(b.get(0), a)) {
                    throw new IllegalArgumentException(
                            "孔洞" + (i + 1) + "与孔洞" + (j + 1) + "不得相互包含");
                }
            }
        }
    }

    /** 校验单个环：顶点数、无重复点、无零长度边、无自交。 */
    private static void validateRing(List<Point> ring, String name, int min, int max) {
        if (ring == null) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        int n = ring.size();
        if (n < min || n > max) {
            throw new IllegalArgumentException(
                    name + "顶点数必须在 " + min + "~" + max + " 之间，实际 " + n);
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (ring.get(i).equals(ring.get(j))) {
                    throw new IllegalArgumentException(
                            name + "存在重复顶点 (" + ring.get(i).x() + "," + ring.get(i).y()
                                    + ")；首尾顶点不用重复");
                }
            }
        }
        // 自交：非相邻边不得相交（i 与 i+1 相邻；边 0 与边 n-1 共享首点，也相邻）
        for (int i = 0; i < n; i++) {
            Point a1 = ring.get(i);
            Point a2 = ring.get((i + 1) % n);
            if (a1.equals(a2)) {
                throw new IllegalArgumentException(name + "存在零长度边");
            }
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) {
                    continue;
                }
                Point b1 = ring.get(j);
                Point b2 = ring.get((j + 1) % n);
                if (PolygonGeometry.segmentsIntersect(a1, a2, b1, b2)) {
                    throw new IllegalArgumentException(name + "存在自交：边("
                            + i + ")与边(" + j + ")相交或共线重叠");
                }
            }
        }
    }

    /** 两个环的边是否存在任一相交（含接触与共线重叠）。 */
    private static boolean ringsIntersect(List<Point> a, List<Point> b) {
        for (int i = 0; i < a.size(); i++) {
            Point a1 = a.get(i);
            Point a2 = a.get((i + 1) % a.size());
            for (int j = 0; j < b.size(); j++) {
                Point b1 = b.get(j);
                Point b2 = b.get((j + 1) % b.size());
                if (PolygonGeometry.segmentsIntersect(a1, a2, b1, b2)) {
                    return true;
                }
            }
        }
        return false;
    }
}
