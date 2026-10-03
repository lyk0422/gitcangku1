package com.example.starter.race.domain;

/**
 * 芯片更换时的分段读数归属纯逻辑，不涉及数据库与事务。
 *
 * <p>规则（时间均为 Unix 毫秒时间戳，UTC；区间左闭右开）：
 * <ul>
 *   <li>读数接收时刻早于更换生效时刻：归旧芯片；</li>
 *   <li>读数接收时刻等于或晚于生效时刻：归新芯片；</li>
 *   <li>读数已归属的芯片既不是旧芯片也不是新芯片时，无法唯一归属，
 *       调用方必须整次回滚。</li>
 * </ul>
 */
public final class ChipAttribution {

    private ChipAttribution() {
    }

    /**
     * 计算一条读数在更换后的归属芯片。
     *
     * @param readingAt   读数接收时刻（Unix毫秒时间戳）
     * @param effectiveAt 更换生效时刻（Unix毫秒时间戳）
     * @param oldChipCode 旧芯片代码
     * @param newChipCode 新芯片代码
     * @return 重算后应归属的芯片代码
     */
    public static String chipFor(
            long readingAt, long effectiveAt, String oldChipCode, String newChipCode) {
        return readingAt < effectiveAt ? oldChipCode : newChipCode;
    }

    /**
     * 校验一条读数能否在旧新芯片之间被唯一重算归属。
     *
     * @param currentChip 读数当前归属芯片；null 表示尚未归属
     * @param oldChip     旧芯片代码
     * @param newChip     新芯片代码
     * @return 无法唯一归属时的错误信息；当前未归属或已归旧/新芯片时为 null
     */
    public static String uniquenessViolation(String currentChip, String oldChip, String newChip) {
        if (currentChip == null || currentChip.equals(oldChip) || currentChip.equals(newChip)) {
            return null;
        }
        return "分段读数已归属第三块芯片 " + currentChip + "，无法在 " + oldChip
                + " 与 " + newChip + " 之间唯一归属";
    }
}