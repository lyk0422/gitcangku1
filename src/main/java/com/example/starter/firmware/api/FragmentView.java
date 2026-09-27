package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseFragment;

/**
 * 发布分片登记视图，按 seqNo 升序返回。
 */
public record FragmentView(int seqNo, String digest) {

    public static FragmentView of(ReleaseFragment fragment) {
        return new FragmentView(fragment.seqNo(), fragment.digest());
    }
}
