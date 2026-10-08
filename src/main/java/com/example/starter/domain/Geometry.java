package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 二维平面几何判定。本题只模拟二维平面，不用于真实飞行。
 * 禁飞区为非退化轴对齐闭矩形，边界接触也算命中。
 */
public final class Geometry {

    private Geometry() {
    }

    /**
     * 判断一条线段（含端点）是否与轴对齐闭矩形相交。
     * 覆盖端点在外而线段穿越矩形内部、以及仅接触边界的情况。
     *
     * @param ax 端点 A 的 X（米）
     * @param ay 端点 A 的 Y（米）
     * @param bx 端点 B 的 X（米）
     * @param by 端点 B 的 Y（米）
     * @param xMin 矩形左边界（含）
     * @param yMin 矩形下边界（含）
     * @param xMax 矩形右边界（含）
     * @param yMax 矩形上边界（含）
     * @return true 表示线段有点位于矩形内或边界上
     */
    public static boolean segmentHitsRectangle(long ax, long ay, long bx, long by,
                                               int xMin, int yMin, int xMax, int yMax) {
        // 1. 任一端点在闭矩形内（含边界）即命中
        if (inRectangle(ax, ay, xMin, yMin, xMax, yMax)
                || inRectangle(bx, by, xMin, yMin, xMax, yMax)) {
            return true;
        }
        // 2. 端点均在矩形外：用 Liang-Barsky 直线段裁剪算法，
        //    判断线段参数区间 t∈[0,1] 是否与矩形的四个半空间交集非空。
        //    端点已在矩形外，交集非空即意味着线段穿过矩形内部或边界，
        //    包括仅擦过边界（接触边界）的情况。
        long dx = bx - ax;
        long dy = by - ay;
        double t0 = 0.0d;
        double t1 = 1.0d;
        // p*x <= q 形式：左 x>=xMin、右 x<=xMax、下 y>=yMin、上 y<=yMax
        long[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - xMin, xMax - ax, ay - yMin, yMax - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0L) {
                // 线段与该边界平行且在外侧，则不可能相交
                if (q[i] < 0.0d) {
                    return false;
                }
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0L) {
                    if (r > t1) {
                        return false;
                    }
                    if (r > t0) {
                        t0 = r;
                    }
                } else {
                    if (r < t0) {
                        return false;
                    }
                    if (r < t1) {
                        t1 = r;
                    }
                }
            }
        }
        return t0 <= t1;
    }

    /**
     * 判断有序航点组成的折线（含每条线段及端点）是否与闭矩形相交。
     *
     * @param points 按顺序连接的航点
     * @param xMin   矩形左边界（含）
     * @param yMin   矩形下边界（含）
     * @param xMax   矩形右边界（含）
     * @param yMax   矩形上边界（含）
     * @return true 表示相交、位于其中或仅接触边界
     */
    public static boolean polylineHitsRectangle(List<Point> points,
                                                int xMin, int yMin, int xMax, int yMax) {
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            if (segmentHitsRectangle(a.x(), a.y(), b.x(), b.y(), xMin, yMin, xMax, yMax)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断点是否位于闭矩形内（含边界）。
     */
    public static boolean inRectangle(long x, long y,
                                      int xMin, int yMin, int xMax, int yMax) {
        return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
    }

    /**
     * 判断一条二次贝塞尔曲线段（含全部 t∈[0,1] 的点）是否与轴对齐闭矩形相交。
     * 内部穿越、边界接触、单点相切均返回 true。
     *
     * <p>精确解析法，不做有限离散采样，也不以控制点包围盒相交直接判阻断：
     * B(t)=(1-t)^2*P0+2t(1-t)*P1+t^2*P2 的两个坐标均为整系数二次多项式，
     * 分别求坐标落入闭区间 [min,max] 的 t 区间集合，再求 x/y 两集合在 [0,1] 内
     * 的交集；交集非空（含单点相切）即曲线上存在点位于矩形内或边界上。</p>
     *
     * @param seg 二次曲线段（P0/P1/P2 为整数坐标）
     */
    public static boolean quadraticHitsRectangle(CurveSegment seg,
                                                 int xMin, int yMin, int xMax, int yMax) {
        List<double[]> xIntervals = quadraticFeasibleIntervals(
                seg.p0().x(), seg.p1().x(), seg.p2().x(), xMin, xMax);
        if (xIntervals.isEmpty()) {
            return false;
        }
        List<double[]> yIntervals = quadraticFeasibleIntervals(
                seg.p0().y(), seg.p1().y(), seg.p2().y(), yMin, yMax);
        return !intersectIntervals(xIntervals, yIntervals).isEmpty();
    }

    /**
     * 判断有序二次曲线段组成的曲线（段间首尾端点相等）是否与闭矩形相交。
     */
    public static boolean curveHitsRectangle(List<CurveSegment> segments,
                                             int xMin, int yMin, int xMax, int yMax) {
        for (CurveSegment seg : segments) {
            if (quadraticHitsRectangle(seg, xMin, yMin, xMax, yMax)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 求整系数二次多项式 q(t)=a(1-t)^2+2bt(1-t)+ct^2 在 t∈[0,1] 上取值落入
     * 闭区间 [low,high] 的全部 t，返回互不相交、按起点排序的闭区间；
     * 仅单点满足（边界相切）时返回零长度区间 [t,t]。
     *
     * <p>以 q(t)=low / q(t)=high 的实根把 [0,1] 分区，开区间内符号恒定，
     * 用中点判定；根点本身恰在边界上，必然满足。整系数判别式精确判断有无实根，
     * 不把“差一点点”的未交误判为相切。</p>
     */
    private static List<double[]> quadraticFeasibleIntervals(int a, int b, int c,
                                                             int low, int high) {
        // q(t) = A t^2 - 2B t + a，其中 A=a-2b+c，B=a-b
        int bigA = a - 2 * b + c;
        int linB = -2 * (a - b);

        List<Double> critical = new ArrayList<>();
        critical.add(0.0d);
        critical.add(1.0d);
        addEquationRoots(bigA, linB, a - low, critical);
        addEquationRoots(bigA, linB, a - high, critical);

        List<Double> sorted = new ArrayList<>();
        for (Double t : critical) {
            if (t >= -T_EPS && t <= 1.0d + T_EPS) {
                sorted.add(Math.min(1.0d, Math.max(0.0d, t)));
            }
        }
        sorted.sort(Double::compare);
        List<Double> roots = new ArrayList<>();
        for (Double t : sorted) {
            if (roots.isEmpty() || Math.abs(t - roots.get(roots.size() - 1)) > T_EPS) {
                roots.add(t);
            }
        }

        int n = roots.size();
        boolean[] segmentIn = new boolean[n - 1];
        for (int i = 0; i + 1 < n; i++) {
            double mid = (roots.get(i) + roots.get(i + 1)) / 2.0d;
            double value = quadBernstein(a, b, c, mid);
            // 开区间内符号恒定：中点满足则整段内部满足（端点为边界根或已判端点）
            segmentIn[i] = value >= low - V_EPS && value <= high + V_EPS;
        }
        // 内部临界点都是 q=low 或 q=high 的根，且 low<high，故根点恒在闭区间内；
        // 两端点需实际判定。
        boolean[] pointIn = new boolean[n];
        pointIn[0] = withinQuad(a, b, c, roots.get(0), low, high);
        pointIn[n - 1] = withinQuad(a, b, c, roots.get(n - 1), low, high);
        for (int i = 1; i < n - 1; i++) {
            pointIn[i] = true;
        }

        // 闭小区间 [r[i],r[i+1]] 可行：内部可行且两端点可行
        boolean[] pieceIn = new boolean[n - 1];
        for (int i = 0; i + 1 < n; i++) {
            pieceIn[i] = segmentIn[i] && pointIn[i] && pointIn[i + 1];
        }

        // 扫描合并相邻可行闭小区间；孤立可行根点形成零长度区间（单点相切）
        List<double[]> result = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (!pointIn[i]) {
                i++;
                continue;
            }
            double start = roots.get(i);
            double end = start;
            int k = i;
            while (k + 1 < n && pieceIn[k]) {
                end = roots.get(k + 1);
                k++;
            }
            result.add(new double[]{start, end});
            i = k + 1;
        }
        return result;
    }

    private static boolean withinQuad(int a, int b, int c, double t, int low, int high) {
        double value = quadBernstein(a, b, c, t);
        return value >= low - V_EPS && value <= high + V_EPS;
    }

    /** Bernstein 形式求值，t∈[0,1] 下数值稳定。 */
    private static double quadBernstein(int a, int b, int c, double t) {
        double u = 1.0d - t;
        return a * u * u + 2.0d * b * t * u + c * t * t;
    }

    /** 求 A t^2 + B t + C = 0 的实根并入集合（判别式用 long 精确计算）。 */
    private static void addEquationRoots(int bigA, int bigB, int cc, List<Double> roots) {
        if (bigA == 0) {
            if (bigB != 0) {
                roots.add(-(double) cc / bigB);
            }
            return;
        }
        long disc = (long) bigB * bigB - 4L * bigA * cc;
        if (disc < 0L) {
            return;
        }
        double sqrt = Math.sqrt(disc);
        roots.add((-bigB - sqrt) / (2.0d * bigA));
        if (disc > 0L) {
            roots.add((-bigB + sqrt) / (2.0d * bigA));
        }
    }

    /** 两组互不相交、已排序闭区间（含零长度）求交，返回非空交集列表。 */
    private static List<double[]> intersectIntervals(List<double[]> xs, List<double[]> ys) {
        List<double[]> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < xs.size() && j < ys.size()) {
            double lo = Math.max(xs.get(i)[0], ys.get(j)[0]);
            double hi = Math.min(xs.get(i)[1], ys.get(j)[1]);
            if (lo <= hi + T_EPS) {
                out.add(new double[]{lo, Math.max(lo, hi)});
            }
            if (xs.get(i)[1] < ys.get(j)[1]) {
                i++;
            } else {
                j++;
            }
        }
        return out;
    }

    /** t 参数比较容差：远小于整数坐标下真实未交间隙，仅吸收 double 求根误差。 */
    private static final double T_EPS = 1.0e-10;
    /** 坐标值容差（米）：覆盖求值舍入，整数坐标相切与亚米级真实未交可被区分。 */
    private static final double V_EPS = 1.0e-8;
}
