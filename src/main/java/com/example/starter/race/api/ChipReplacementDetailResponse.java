package com.example.starter.race.api;

import java.util.List;

/**
 * 芯片更换明细查询响应：更换记录与每条被重算分段的前后归属。
 *
 * @param replacement 更换记录本体
 * @param details     被重算的分段明细，按检查点顺序稳定排列
 */
public record ChipReplacementDetailResponse(
        ChipReplacementResponse replacement,
        List<Attribution> details
) {

    /**
     * 单条分段读数在更换前后的芯片归属。
     *
     * @param timingId       分段记录ID
     * @param checkpointCode 检查点代码
     * @param position       检查点顺序
     * @param chipBefore     重算前归属芯片；此前未归属为 null
     * @param chipAfter      重算后归属芯片
     */
    public record Attribution(
            String timingId,
            String checkpointCode,
            int position,
            String chipBefore,
            String chipAfter
    ) {
    }
}