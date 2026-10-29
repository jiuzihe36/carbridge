package com.carbridge.carplay.bridge;

import com.carbridge.app.HuProto;

/**
 * CarPlay 的 HID 硬键 → CarLife 的小车机键码。
 */
public final class CarHardKeyMap {

    private CarHardKeyMap() {
    }

    /**
     * HID usage → CarLife 键码；没有对应键返回 0（调用方丢弃）。
     *
     * <p>Telephony 页的 {@code Hook Switch(0x20)} 是「接听/挂断」二义键：
     * 这里统一映射成 {@code PHONE_CALL}，由 {@code HuClient} 按当前通话状态
     * 决定到底发接听还是挂断。
     */
    public static int toCarLifeKeycode(int usage) {
        switch (usage) {
            // ── Consumer 页：媒体 ──
            case 0xB0: return HuProto.CarHardKey.MEDIA_START;        // 播放
            case 0xB1: return HuProto.CarHardKey.MEDIA_STOP;         // 暂停
            case 0xB7: return HuProto.CarHardKey.MEDIA_STOP;         // 停止（USB HID 规范）
            case 0xCC: return HuProto.CarHardKey.MEDIA_STOP;         // 停止/弹出
            // ⚠️ 方向盘最常见的是 **Play/Pause(0xCD)** 而不是分开的 Play/Pause。
            //    CarLife 没有「切换」键码，只能映射成「播放」——
            //    暂停状态下按一下会开始播；播放中按一下是 no-op（不会误暂停）。
            case 0xCD: return HuProto.CarHardKey.MEDIA_START;        // 播放/暂停（切换键）
            case 0xB5: return HuProto.CarHardKey.SELECTOR_NEXT;      // 下一曲
            case 0xB6: return HuProto.CarHardKey.SELECTOR_PREVIOUS;  // 上一曲
            case 0xB3: return HuProto.CarHardKey.SEEK_ADD;          // 快进
            case 0xB4: return HuProto.CarHardKey.SEEK_SUB;          // 快退
            case 0xCF: return HuProto.CarHardKey.VR_START;          // 语音（Siri 键）
            case 0xE2: return HuProto.CarHardKey.MUTE;              // 静音
            case 0xE9: return HuProto.CarHardKey.VOLUME_ADD;        // 音量+
            case 0xEA: return HuProto.CarHardKey.VOLUME_SUB;        // 音量-
            // ── Telephony 页：电话 ──
            case 0x20: return HuProto.CarHardKey.PHONE_CALL;        // 接听/挂断（二义）
            case 0x26: return HuProto.CarHardKey.PHONE_END;         // 挂断
            case 0x2F: return HuProto.CarHardKey.MUTE;              // 通话静音
            default: return 0;
        }
    }
}
