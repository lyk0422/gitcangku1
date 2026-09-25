package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 空域单元网格与时空桶工具。
 *
 * <p>空域划分为 1000 米 x 1000 米的固定网格，单元标识为 "C<gx>_<gy>"，
 * 其中 gx = floorDiv(x, 1000)、gy = floorDiv(y, 1000)，可负。
 * 单元几何取其闭正方形 [gx*1000, gx*1000+1000] x [gy*1000, gy*1000+1000]。</p>
 *
 * <p>时间维度为 15 分钟 UTC 时间桶（900000 毫秒），桶起始时刻必须对齐。</p>
 */
public final class Cells {

    /** 网格边长，单位米。 */
    public static final long CELL_SIZE = 1000L;
    /** 时间桶长度，15 分钟，单位毫秒。 */
    public static final long BUCKET_MILLIS = 15L * 60L * 1000L;

    private Cells() {
    }

    /** 坐标所属单元标识。 */
    public static String cellIdOf(long x, long y) {
        return cellId(Math.floorDiv(x, CELL_SIZE), Math.floorDiv(y, CELL_SIZE));
    }

    /** 网格坐标拼装单元标识。 */
    public static String cellId(long gx, long gy) {
        return "C" + gx + "_" + gy;
    }

    /** 判断是否为合法单元标识（C<gx>_<gy>，gx/gy 为可负整数）。 */
    public static boolean isValidCellId(String cellId) {
        return parse(cellId) != null;
    }

    /** 解析单元标识为 [gx, gy]；非法格式返回 null。 */
    public static long[] parse(String cellId) {
        if (cellId == null || cellId.length() < 4 || cellId.charAt(0) != 'C') {
            return null;
        }
        int sep = cellId.lastIndexOf('_');
        if (sep <= 1 || sep == cellId.length() - 1) {
            return null;
        }
        try {
            long gx = Long.parseLong(cellId.substring(1, sep));
            long gy = Long.parseLong(cellId.substring(sep + 1));
            return new long[]{gx, gy};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 判断时间桶起始是否对齐 15 分钟 UTC 桶。 */
    public static boolean isAlignedBucketStart(long bucketStart) {
        return Math.floorMod(bucketStart, BUCKET_MILLIS) == 0L;
    }

    /**
     * 判断两个单元是否相同或八邻接（|dgx| <= 1 且 |dgy| <= 1）。
     * 非法标识返回 false。
     */
    public static boolean adjacentOrSame(String a, String b) {
        long[] pa = parse(a);
        long[] pb = parse(b);
        if (pa == null || pb == null) {
            return false;
        }
        return Math.abs(pa[0] - pb[0]) <= 1 && Math.abs(pa[1] - pb[1]) <= 1;
    }

    /**
     * 判断单元闭正方形是否与闭矩形（禁飞区）相交，边界接触也算相交。
     */
    public static boolean cellIntersectsRectangle(String cellId,
                                                  int xMin, int yMin, int xMax, int yMax) {
        long[] g = parse(cellId);
        if (g == null) {
            return false;
        }
        long cxMin = g[0] * CELL_SIZE;
        long cyMin = g[1] * CELL_SIZE;
        long cxMax = cxMin + CELL_SIZE;
        long cyMax = cyMin + CELL_SIZE;
        return cxMin <= xMax && xMin <= cxMax && cyMin <= yMax && yMin <= cyMax;
    }

    /**
     * 计算折线的有序穿越单元序列（supercover）：线段经过的每个单元按顺序列出，
     * 连续重复单元只保留一个。序列中相邻单元保证相同或八邻接。
     */
    public static List<String> traversalCells(List<Point> points) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            for (String cell : segmentCells(a.x(), a.y(), b.x(), b.y())) {
                if (cells.isEmpty() || !cells.get(cells.size() - 1).equals(cell)) {
                    cells.add(cell);
                }
            }
        }
        return cells;
    }

    /**
     * 线段的有序穿越单元序列（含起点与终点所在单元）。
     * 使用网格 DDA 遍历；恰好在格线交点处穿越时按对角步进（八邻接），结果确定。
     */
    static List<String> segmentCells(long x0, long y0, long x1, long y1) {
        List<String> cells = new ArrayList<>();
        long gx = Math.floorDiv(x0, CELL_SIZE);
        long gy = Math.floorDiv(y0, CELL_SIZE);
        long gx1 = Math.floorDiv(x1, CELL_SIZE);
        long gy1 = Math.floorDiv(y1, CELL_SIZE);
        cells.add(cellId(gx, gy));

        long dx = x1 - x0;
        long dy = y1 - y0;
        int stepX = Long.signum(dx);
        int stepY = Long.signum(dy);
        // 下一条 x/y 格线位置与参数 t
        double tMaxX = dx == 0L ? Double.POSITIVE_INFINITY
                : ((gx + (stepX > 0 ? 1 : 0)) * CELL_SIZE - x0) / (double) dx;
        double tMaxY = dy == 0L ? Double.POSITIVE_INFINITY
                : ((gy + (stepY > 0 ? 1 : 0)) * CELL_SIZE - y0) / (double) dy;
        double tDeltaX = dx == 0L ? Double.POSITIVE_INFINITY
                : CELL_SIZE / (double) Math.abs(dx);
        double tDeltaY = dy == 0L ? Double.POSITIVE_INFINITY
                : CELL_SIZE / (double) Math.abs(dy);

        while (gx != gx1 || gy != gy1) {
            if (tMaxX < tMaxY) {
                gx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxX) {
                gy += stepY;
                tMaxY += tDeltaY;
            } else {
                // 恰好穿过格线交点：对角步进
                gx += stepX;
                gy += stepY;
                tMaxX += tDeltaX;
                tMaxY += tDeltaY;
            }
            cells.add(cellId(gx, gy));
        }
        return cells;
    }
}
