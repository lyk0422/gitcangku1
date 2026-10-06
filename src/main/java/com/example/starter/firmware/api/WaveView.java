package com.example.starter.firmware.api;

import java.util.List;

/**
 * 拓扑波次视图：领取成功即冻结成员集合，同 requestId 重放返回本快照，不按当前健康重选。
 */
public record WaveView(long waveId, long releaseId, int waveSeq, List<WaveMemberView> members) {
}
