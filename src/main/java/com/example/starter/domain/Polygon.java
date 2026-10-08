package com.example.starter.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 带孔洞多边形区域。
 *
 * <p>禁飞实体 = 外环闭区域内部 去掉 各孔洞闭区域内部；外环边与孔洞边均仍禁飞。
 * 线段判定全程使用整数/有理精确运算（{@link BigInteger}），不做固定步长采样，
 * 任意窄的禁飞实体与擦边接触都不会被跳过或误判。</p>
 *
 * @param outerRing 外环顶点（首尾不重复，3~10 个点）
 * @param holes     孔洞顶点列表（0~2 个，每个 3~8 个点，首尾不重复）
 */
public record Polygon(List<Point> outerRing, List<List<Point>> holes) {

    public Polygon {
        outerRing = List.copyOf(outerRing);
        List<List<Point>> copied = new ArrayList<>(holes.size());
        for (List<Point> hole : holes) {
            copied.add(List.copyOf(hole));
        }
        holes = List.copyOf(copied);
    }

    /**
     * 有序航点组成的折线（含每条线段及端点）是否命中禁飞实体。
     * 实体内部、外环边、孔洞边都算命中；仅停留在孔洞内部（合法通道）不算。
     */
    public boolean polylineHits(List<Point> route) {
        for (int i = 0; i + 1 < route.size(); i++) {
            if (segmentHits(route.get(i), route.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 闭线段 ab 是否与禁飞实体相交（含任一环边接触）。
     * 步骤：环边接触判定 → 端点实体判定 → 沿线段在所有交点参数处切分，
     * 每个开区间取有理中点精确归类，只要某一段位于实体中即命中。
     */
    public boolean segmentHits(Point a, Point b) {
        // 1. 线段与外环边或任一孔洞边相交（含端点接触、重叠、擦边）
        if (PolygonGeometry.segmentTouchesRingBoundary(a, b, outerRing)) {
            return true;
        }
        for (List<Point> hole : holes) {
            if (PolygonGeometry.segmentTouchesRingBoundary(a, b, hole)) {
                return true;
            }
        }
        // 2. 零长度线段：边界已查，余下按点是否在实体内部判定
        if (a.equals(b)) {
            return solidContains(RatPoint.of(a));
        }
        // 3. 端点在实体内部（环边上的端点已在第 1 步返回）
        if (solidContains(RatPoint.of(a)) || solidContains(RatPoint.of(b))) {
            return true;
        }
        // 4. 收集线段与所有环边交点的参数 t∈[0,1]，切分开区间后逐段精确归类
        TreeSet<Fraction> params = new TreeSet<>();
        params.add(Fraction.ZERO);
        params.add(Fraction.ONE);
        collectContactParams(a, b, outerRing, params);
        for (List<Point> hole : holes) {
            collectContactParams(a, b, hole, params);
        }
        Fraction prev = null;
        for (Fraction t : params) {
            if (prev != null) {
                RatPoint mid = RatPoint.at(a, b, prev.midpoint(t));
                // 与环边重合的区间：中点精确落在环边上，仍属禁飞
                if (onAnyBoundary(mid) || solidContains(mid)) {
                    return true;
                }
            }
            prev = t;
        }
        return false;
    }

    /** 有理点是否位于禁飞实体内部：严格在外环内、且不严格在任一孔洞内。 */
    private boolean solidContains(RatPoint p) {
        if (!pointStrictlyInsideOuter(p)) {
            return false;
        }
        for (List<Point> hole : holes) {
            if (pointStrictlyInsideRing(p, hole)) {
                return false;
            }
        }
        return true;
    }

    private boolean pointStrictlyInsideOuter(RatPoint p) {
        return pointStrictlyInsideRing(p, outerRing);
    }

    private boolean onAnyBoundary(RatPoint p) {
        if (pointOnRingBoundary(p, outerRing)) {
            return true;
        }
        for (List<Point> hole : holes) {
            if (pointOnRingBoundary(p, hole)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集线段 ab 与某环所有边相交处的参数 t。一般相交按叉积比例求得精确分数；
     * 共线重叠时把落在 ab 上的环顶点投影参数加入，以切出与环边重合的区间。
     */
    private static void collectContactParams(Point a, Point b, List<Point> ring, Set<Fraction> out) {
        long dx = b.x() - a.x();
        long dy = b.y() - a.y();
        long len2 = dx * dx + dy * dy;
        for (int i = 0; i < ring.size(); i++) {
            Point c = ring.get(i);
            Point d = ring.get((i + 1 == ring.size()) ? 0 : i + 1);
            long ex = d.x() - c.x();
            long ey = d.y() - c.y();
            long denom = dx * ey - dy * ex;
            if (denom == 0L) {
                if (PolygonGeometry.pointOnSegment(c, a, b)) {
                    long acx = c.x() - a.x();
                    long acy = c.y() - a.y();
                    out.add(Fraction.of(acx * dx + acy * dy, len2));
                }
                if (PolygonGeometry.pointOnSegment(d, a, b)) {
                    long adx = d.x() - a.x();
                    long ady = d.y() - a.y();
                    out.add(Fraction.of(adx * dx + ady * dy, len2));
                }
            } else {
                long acx = c.x() - a.x();
                long acy = c.y() - a.y();
                long tNum = acx * ey - acy * ex;
                long uNum = acx * dy - acy * dx;
                if (inUnit(tNum, denom) && inUnit(uNum, denom)) {
                    out.add(Fraction.of(tNum, denom));
                }
            }
        }
    }

    /** 0 <= num/den <= 1（den 可能为负）。 */
    private static boolean inUnit(long num, long den) {
        return den > 0 ? num >= 0 && num <= den : num <= 0 && num >= den;
    }

    // ====================== 有理点精确几何 ======================

    /**
     * 有理坐标点 (x/d, y/d)，d 恒正。交点中点等无法用整数表示的位置用它判定，
     * 避免浮点误差导致贴边/窄通道误判。
     */
    record RatPoint(BigInteger x, BigInteger y, BigInteger d) {
        static RatPoint of(Point p) {
            return new RatPoint(BigInteger.valueOf(p.x()), BigInteger.valueOf(p.y()), BigInteger.ONE);
        }

        /** 线段 ab 上参数 t 处的点。 */
        static RatPoint at(Point a, Point b, Fraction t) {
            BigInteger den = t.denominator();
            BigInteger x = den.multiply(BigInteger.valueOf(a.x()))
                    .add(BigInteger.valueOf(b.x() - a.x()).multiply(t.numerator()));
            BigInteger y = den.multiply(BigInteger.valueOf(a.y()))
                    .add(BigInteger.valueOf(b.y() - a.y()).multiply(t.numerator()));
            return new RatPoint(x, y, den);
        }
    }

    /** 有理点是否在环边上（含端点）。 */
    static boolean pointOnRingBoundary(RatPoint p, List<Point> ring) {
        for (int i = 0; i < ring.size(); i++) {
            Point a = ring.get(i);
            Point b = ring.get((i + 1 == ring.size()) ? 0 : i + 1);
            BigInteger ax = BigInteger.valueOf(a.x()).multiply(p.d());
            BigInteger ay = BigInteger.valueOf(a.y()).multiply(p.d());
            BigInteger ex = BigInteger.valueOf(b.x() - a.x());
            BigInteger ey = BigInteger.valueOf(b.y() - a.y());
            BigInteger cross = p.x().subtract(ax).multiply(ey)
                    .subtract(p.y().subtract(ay).multiply(ex));
            if (cross.signum() != 0) {
                continue;
            }
            BigInteger bx = BigInteger.valueOf(b.x()).multiply(p.d());
            BigInteger by = BigInteger.valueOf(b.y()).multiply(p.d());
            boolean inBox = between(p.x(), ax, bx) && between(p.y(), ay, by);
            if (inBox) {
                return true;
            }
        }
        return false;
    }

    private static boolean between(BigInteger v, BigInteger lo, BigInteger hi) {
        if (lo.compareTo(hi) > 0) {
            BigInteger tmp = lo;
            lo = hi;
            hi = tmp;
        }
        return v.compareTo(lo) >= 0 && v.compareTo(hi) <= 0;
    }

    /**
     * 有理点是否严格在环内部（不含环边）。射线法，水平向右射线，
     * 水平端点按上端点归属，所有比较使用 BigInteger 精确完成。
     */
    static boolean pointStrictlyInsideRing(RatPoint p, List<Point> ring) {
        boolean inside = false;
        for (int i = 0; i < ring.size(); i++) {
            Point a = ring.get(i);
            Point b = ring.get((i + 1 == ring.size()) ? 0 : i + 1);
            BigInteger ay = BigInteger.valueOf(a.y()).multiply(p.d());
            BigInteger by = BigInteger.valueOf(b.y()).multiply(p.d());
            boolean crosses = ay.compareTo(p.y()) > 0 != by.compareTo(p.y()) > 0;
            if (!crosses) {
                continue;
            }
            // 交点 x：ax + (py-ay)*(bx-ax)/(by-ay)，整体放大到 p.d 分母后与 p.x 比较。
            // 注意 den 可能为负（边朝下），分数比大小的方向必须随分母符号翻转。
            BigInteger den = by.subtract(ay);
            BigInteger numY = p.y().subtract(ay);
            BigInteger xIntersect = BigInteger.valueOf(a.x()).multiply(den)
                    .add(numY.multiply(BigInteger.valueOf(b.x() - a.x())));
            int cmp = xIntersect.multiply(p.d()).compareTo(p.x().multiply(den));
            if (den.signum() < 0) {
                cmp = -cmp;
            }
            if (cmp > 0) {
                inside = !inside;
            }
        }
        return inside;
    }
}
