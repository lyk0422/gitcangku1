package com.example.starter.domain;

import java.math.BigInteger;
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

    // ===================== 二次贝塞尔曲线精确求交 =====================

    /**
     * 判断一条二次贝塞尔曲线段是否与轴对齐闭矩形相交。
     *
     * <p>曲线参数式 B(t)=(1-t)^2·P0+2t(1-t)·P1+t^2·P2，t∈[0,1]。
     * x、y 坐标分别为整系数一元二次式。先看端点是否落在矩形内，再对
     * x=xMin/xMax、y=yMin/yMax 四条边所在直线求根，只保留 t∈[0,1] 且
     * 另一坐标落在闭区间内的候选参数；判别式为零的重根（单点相切）同样命中。
     * 全部根位置与坐标比较都用整数（BigInteger 平方）完成，不使用浮点、
     * 不采有限离散点，也不以控制点凸包/包围盒相交直接判阻断。</p>
     *
     * @param c    二次曲线段（整数控制点）
     * @param xMin 矩形左边界（含）
     * @param yMin 矩形下边界（含）
     * @param xMax 矩形右边界（含）
     * @param yMax 矩形上边界（含）
     * @return true 表示曲线有点位于矩形内、边界上，或与边界单点相切
     */
    public static boolean quadraticHitsRectangle(QuadraticCurve c,
                                                 int xMin, int yMin, int xMax, int yMax) {
        // 0. 端点在闭矩形内（含边界）即命中
        if (inRectangle(c.p0().x(), c.p0().y(), xMin, yMin, xMax, yMax)
                || inRectangle(c.p2().x(), c.p2().y(), xMin, yMin, xMax, yMax)) {
            return true;
        }
        long[] xq = quadraticCoefficients(c.p0().x(), c.p1().x(), c.p2().x());
        long[] yq = quadraticCoefficients(c.p0().y(), c.p1().y(), c.p2().y());
        // 1. x 取左/右边所在直线的根，核对 y 是否在闭区间内
        if (rootTouchesRectangle(xq, yq, xMin, yMin, yMax)
                || rootTouchesRectangle(xq, yq, xMax, yMin, yMax)) {
            return true;
        }
        // 2. y 取下/上边所在直线的根，核对 x 是否在闭区间内
        return rootTouchesRectangle(yq, xq, yMin, xMin, xMax)
                || rootTouchesRectangle(yq, xq, yMax, xMin, xMax);
    }

    /**
     * 判断有序二次曲线段组成的曲线航线（含每段曲线及公共端点）是否与闭矩形相交。
     *
     * @param curve 按顺序首尾相接的二次曲线段
     * @param xMin  矩形左边界（含）
     * @param yMin  矩形下边界（含）
     * @param xMax  矩形右边界（含）
     * @param yMax  矩形上边界（含）
     * @return true 表示任一段曲线相交、位于其中或仅接触边界
     */
    public static boolean curveHitsRectangle(RouteCurve curve,
                                             int xMin, int yMin, int xMax, int yMax) {
        for (QuadraticCurve segment : curve.segments()) {
            if (quadraticHitsRectangle(segment, xMin, yMin, xMax, yMax)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 由三个整数控制点坐标求该坐标方向的二次式系数 [A,B,C]：
     * f(t)=A·t²+B·t+C，t∈[0,1]；f(0)=p0、f(1)=p2。
     */
    private static long[] quadraticCoefficients(int p0, int p1, int p2) {
        return new long[]{p0 - 2L * p1 + p2, 2L * (p1 - p0), p0};
    }

    /**
     * 判断方程 f(t)=level（f 为整系数二次式，t∈[0,1]）是否存在根，
     * 使另一坐标 g(t) 落在闭区间 [otherMin,otherMax]。重根（相切）同样成立。
     */
    private static boolean rootTouchesRectangle(long[] f, long[] g, long level,
                                                int otherMin, int otherMax) {
        long a = f[0];
        long b = f[1];
        long c0 = f[2] - level; // 解 h(t)=A·t²+B·t+c0=0
        if (a == 0L) {
            if (b == 0L) {
                // f(t) 恒等于 level：整段曲线压在该边线上，是否命中取决于另一坐标值域
                return c0 == 0L && quadraticRangeOverlaps(g, otherMin, otherMax);
            }
            // 一次方程 B·t+c0=0 → t=-c0/B，精确判定 t∈[0,1] 且 g(t) 在闭区间内
            BigInteger num = BigInteger.valueOf(-c0);
            BigInteger den = BigInteger.valueOf(b);
            if (den.signum() < 0) {
                num = num.negate();
                den = den.negate();
            }
            return num.signum() >= 0 && num.compareTo(den) <= 0
                    && rationalPointInOtherRange(g, num, den, otherMin, otherMax);
        }
        long disc = b * b - 4L * a * c0;
        if (disc < 0L) {
            return false; // 无实根，曲线到不了该边线
        }
        // 在根处由 h(t)=0 得 a·g(t)=U·t+V（一次式），把另一坐标的区间判定
        // 化为关于根 t 的一次式比较；乘以 a 后的闭区间端点（与 a 符号无关地取上下界）
        long u = g[1] * a - g[0] * b;
        long v = g[2] * a - g[0] * c0;
        BigInteger bound1 = BigInteger.valueOf(a * (long) otherMin);
        BigInteger bound2 = BigInteger.valueOf(a * (long) otherMax);
        BigInteger lo = bound1.min(bound2);
        BigInteger hi = bound1.max(bound2);
        BigInteger aa = BigInteger.valueOf(a);
        BigInteger bb = BigInteger.valueOf(b);
        BigInteger dd = BigInteger.valueOf(disc);
        // 两根 t=(-B±√D)/(2A)，判别式为 0 时为重根，重复枚举不影响结论
        for (int sgn = 1; sgn >= -1; sgn -= 2) {
            BigInteger s = BigInteger.valueOf(sgn);
            // t∈[0,1]：0 ≤ (-B+s√D)/(2A) ≤ 1
            if (rootLinearCompare(BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO,
                    aa, bb, s, dd) < 0) {
                continue; // t < 0
            }
            if (rootLinearCompare(BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.ZERO,
                    aa, bb, s, dd) > 0) {
                continue; // t > 1
            }
            // lo ≤ U·t+V ≤ hi
            if (rootLinearCompare(BigInteger.valueOf(u), BigInteger.valueOf(v), lo,
                    aa, bb, s, dd) < 0
                    || rootLinearCompare(BigInteger.valueOf(u), BigInteger.valueOf(v), hi,
                    aa, bb, s, dd) > 0) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 比较 (P·t+Q) 与 K 的大小，其中根 t=(-B+s·√D)/(2A)，s∈{+1,-1}。
     *
     * <p>2A·(P·t+Q-K)=2A(Q-K)-PB+P·s·√D，记 M=2A(Q-K)-PB、R=P·s。
     * 所求差的符号为 sign(M+R·√D)·sign(A)；含 √D 的比较通过 BigInteger
     * 平方精确完成。返回 -1/0/1。</p>
     */
    private static int rootLinearCompare(BigInteger p, BigInteger q, BigInteger k,
                                         BigInteger a, BigInteger b, BigInteger s,
                                         BigInteger disc) {
        BigInteger m = a.multiply(BigInteger.TWO).multiply(q.subtract(k)).subtract(p.multiply(b));
        BigInteger r = p.multiply(s);
        int sumSign = signRootSum(m, r, disc);
        return sumSign * Integer.signum(a.signum());
    }

    /**
     * 返回 M + R·√D 的符号（-1/0/1），全部整数精确比较。
     */
    private static int signRootSum(BigInteger m, BigInteger r, BigInteger disc) {
        if (disc.signum() == 0 || r.signum() == 0) {
            return m.signum();
        }
        BigInteger left = r.multiply(r).multiply(disc); // (R·√D)² = R²·D
        if (r.signum() > 0) {
            if (m.signum() >= 0) {
                return 1;
            }
            return left.compareTo(m.multiply(m)); // R√D 与 -M 比大小
        }
        if (m.signum() <= 0) {
            return -1;
        }
        return m.multiply(m).compareTo(left) > 0 ? 1
                : m.multiply(m).compareTo(left) == 0 ? 0 : -1;
    }

    /**
     * 一次方程根 t=num/den（den&gt;0）处，判定 g(t)∈[otherMin,otherMax]，
     * 分母统一为 den²，全部整数比较。
     */
    private static boolean rationalPointInOtherRange(long[] g, BigInteger num, BigInteger den,
                                                     int otherMin, int otherMax) {
        BigInteger num2 = num.multiply(num);
        BigInteger den2 = den.multiply(den);
        BigInteger value = BigInteger.valueOf(g[0]).multiply(num2)
                .add(BigInteger.valueOf(g[1]).multiply(num).multiply(den))
                .add(BigInteger.valueOf(g[2]).multiply(den2));
        return value.compareTo(den2.multiply(BigInteger.valueOf(otherMin))) >= 0
                && value.compareTo(den2.multiply(BigInteger.valueOf(otherMax))) <= 0;
    }

    /**
     * 判断整系数二次式 g(t) 在 t∈[0,1] 上的值域是否与闭整数区间 [lo,hi] 相交。
     * 值域与 [lo,hi] 相交当且仅当“存在点 ≤hi”且“存在点 ≥lo”；
     * 闭区间二次式的最小/最大值只可能出现在端点或顶点，逐点精确比较。
     */
    private static boolean quadraticRangeOverlaps(long[] g, int lo, int hi) {
        BigInteger g0 = BigInteger.valueOf(g[2]);
        BigInteger g1 = BigInteger.valueOf(g[0] + g[1] + g[2]);
        boolean someAtMostHi = g0.compareTo(BigInteger.valueOf(hi)) <= 0
                || g1.compareTo(BigInteger.valueOf(hi)) <= 0;
        boolean someAtLeastLo = g0.compareTo(BigInteger.valueOf(lo)) >= 0
                || g1.compareTo(BigInteger.valueOf(lo)) >= 0;
        long ga = g[0], gb = g[1];
        if (ga != 0L && vertexInUnitInterval(ga, gb)) {
            // 顶点值 gv=gc-gb²/(4ga) 与整数 k 的比较：4ga·gc-gb² 比 4ga·k，
            // ga<0 时乘负数需翻转比较方向
            BigInteger numerator = BigInteger.valueOf(4L * ga * g[2])
                    .subtract(BigInteger.valueOf(gb).pow(2));
            if (compareVertex(numerator, ga, hi) <= 0) {
                someAtMostHi = true;
            }
            if (compareVertex(numerator, ga, lo) >= 0) {
                someAtLeastLo = true;
            }
        }
        return someAtMostHi && someAtLeastLo;
    }

    /** 顶点 t*=-gb/(2ga) 是否落在 [0,1]，分母取正后整数比较。 */
    private static boolean vertexInUnitInterval(long ga, long gb) {
        long num = ga > 0 ? -gb : gb;
        long den = 2L * Math.abs(ga);
        return num >= 0L && num <= den;
    }

    /** 比较顶点值 gv（分子 num=4ga·gc-gb²、分母符号同 ga）与整数 k。 */
    private static int compareVertex(BigInteger num, long ga, long k) {
        int cmp = num.compareTo(BigInteger.valueOf(4L * ga * k));
        return ga > 0 ? cmp : -cmp;
    }
}
