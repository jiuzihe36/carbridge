package com.carbridge.carplay.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 车机「Consumer Control」按键设备的 HID 描述符 → 报告解码表。
 */
public final class HidKeyMap {

    /** Consumer 页（Generic Desktop 之外最常用的多媒体页）。 */
    private static final int USAGE_PAGE_CONSUMER = 0x0C;
    /** Telephony 页（接听/挂断那组键）。 */
    private static final int USAGE_PAGE_TELEPHONY = 0x0B;

    /** 按下标排列的 Usage ID。 */
    public final int[] usages;
    /**
     * true = Array 语义（报告值是 {@link #usages} 的下标，一次只可能有一个值）；
     * false = Variable/位图语义（每个 usage 占 1 bit）。
     */
    public final boolean array;
    /** 这个键设备属于哪一页：0x0C=Consumer（媒体键）/ 0x0B=Telephony（电话键）。 */
    public final int page;
    /**
     * 描述符里声明的 Report ID（0 = 没有）。
     *
     * <p>有 Report ID 时，车机发的报告**第一个字节就是 ID**，真正的按键值从第 2 字节开始。
     * 不剥掉这一字节，Array 语义下会把 ID 当「下标」，Variable 语义下整条位图错一位
     * —— 表现就是一个键都点不出来，或按出完全不相干的键。
     */
    public final int reportId;

    public HidKeyMap(int[] usages, boolean array, int page) {
        this(usages, array, page, 0);
    }

    public HidKeyMap(int[] usages, boolean array, int page, int reportId) {
        this.usages = usages;
        this.array = array;
        this.page = page;
        this.reportId = reportId;
    }

    /**
     * 报告值 → Usage ID；0 或越界返回 -1（0 = 无按键/释放）。
     */
    public int usageFor(int reportValue) {
        if (reportValue == 0) return -1;
        if (!array) return reportValue;
        if (reportValue >= usages.length) return -1;
        int u = usages[reportValue];
        return u == 0 ? -1 : u;
    }

    /**
     * 报告 → 本次<b>按下</b>的 Usage 列表（松开时返回空）。
     */
    public int[] pressedUsages(byte[] report) {
        List<Integer> out = new ArrayList<Integer>();
        if (report == null || report.length == 0) return new int[0];
        // 有 Report ID 且报告里确实带着它 → 跳过第一个字节
        int base = 0;
        if (reportId != 0 && report.length > 1 && (report[0] & 0xFF) == reportId) {
            base = 1;
        }
        if (array) {
            if (base >= report.length) return new int[0];
            int u = usageFor(report[base] & 0xFF);
            if (u > 0) out.add(u);
            return toArray(out);
        }
        for (int i = 0; i < usages.length; i++) {
            int usage = usages[i];
            if (usage == 0) continue;
            int byteIndex = base + i / 8;
            if (byteIndex >= report.length) break;
            int bit = (report[byteIndex] & 0xFF) >> (i % 8) & 0x01;
            if (bit == 1) out.add(usage);
        }
        return toArray(out);
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

        /**
     * 解析 HID 描述符里的按键 Usage 数组。
     */
    public static HidKeyMap parse(byte[] descriptor) {
        if (descriptor == null || descriptor.length < 4) return null;
        int index = 0;
        int page = 0;
        int reportId = 0;
        boolean inKeyApp = false;
        int keyPage = 0;
        List<Integer> usageList = new ArrayList<Integer>();
        boolean isArray = true;

        while (index < descriptor.length) {
            int prefix = descriptor[index] & 0xFF;
            int size = prefix & 0x03;
            int type = (prefix >> 2) & 0x03;
            int tag = (prefix >> 4) & 0x0F;
            int dataBytes = size == 0 ? 0 : size == 1 ? 1 : size == 2 ? 2 : 4;
            if (index + 1 + dataBytes > descriptor.length) break;
            int value = 0;
            for (int off = 0; off < dataBytes; off++) {
                value |= (descriptor[index + 1 + off] & 0xFF) << (8 * off);
            }
            index += 1 + dataBytes;

            if (type == 1 && tag == 0x0) {
                // 全局项：Usage Page
                page = value;
            } else if (type == 1 && tag == 0x8) {
                // 全局项：Report ID（有它时报告首字节是 ID）
                reportId = value;
            } else if (type == 2 && tag == 0x0) {
                // 局部项：Usage（1 或 2 字节）—— 收进当前按键 Application 的数组
                if (inKeyApp) usageList.add(value);
            } else if (type == 0 && tag == 0xA) {
                // 主项：Collection
                if (!inKeyApp
                        && (page == USAGE_PAGE_CONSUMER || page == USAGE_PAGE_TELEPHONY)) {
                    inKeyApp = true;
                    keyPage = page;
                }
            } else if (type == 0 && tag == 0xC) {
                // 主项：EndCollection
                if (inKeyApp) {
                    return usageList.isEmpty() ? null
                            : new HidKeyMap(toArray(usageList), isArray, keyPage, reportId);
                }
            } else if (type == 0 && tag == 0x8) {
                // 主项：Input —— bit1=0 是 Array（值是下标），=1 是 Variable（值是位图）。
                // bit0=1 是 Constant（描述符尾部的补齐位），不参与按键语义。
                if ((value & 0x01) == 0) isArray = (value & 0x02) == 0;
            }
        }
        if (!usageList.isEmpty()) {
            return new HidKeyMap(toArray(usageList), isArray, keyPage, reportId);
        }
        return null;
    }
}
