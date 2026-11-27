package com.carbridge.carplay.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HID 报告描述符解析：取触摸坐标的**逻辑上限（Logical Maximum）**。
 */
public final class HidDescriptor {

    // Global
    public static final int USAGE_PAGE = 0x04;
    public static final int LOGICAL_MIN = 0x14;
    public static final int LOGICAL_MAX = 0x24;
    public static final int REPORT_SIZE = 0x74;
    public static final int REPORT_ID = 0x84;
    public static final int REPORT_COUNT = 0x94;
    // Local
    public static final int USAGE = 0x08;
    public static final int USAGE_MIN = 0x18;
    public static final int USAGE_MAX = 0x28;
    // Main
    public static final int INPUT = 0x80;
    public static final int OUTPUT = 0x90;
    public static final int FEATURE = 0xB0;
    public static final int COLLECTION = 0xA0;
    public static final int END_COLLECTION = 0xC0;

    public static final int TYPE_MAIN = 0;
    public static final int TYPE_GLOBAL = 1;
    public static final int TYPE_LOCAL = 2;

    /** 数字笔/触摸屏 Usage Page。 */
    public static final int PAGE_DIGITIZER = 0x0D;

    private HidDescriptor() {
    }

    /** 一个 Input 主项解析出来的字段。 */
    public static final class Field {
        public final int usagePage;
        /** 本主项声明的第一个 Usage（兼容旧调用方）。 */
        public final int usage;
        /** 本主项声明的**全部** Usage：真车描述符常见「Usage(X), Usage(Y), Input」
         *  一条主项带两个用途，只留最后一个会把 X 丢掉（轴上限退化成 {y,y}）。 */
        public final int[] usages;
        public final int usageMin;
        public final int usageMax;
        public final int logicalMin;
        public final int logicalMax;
        public final int reportId;
        public final int reportSize;
        public final int reportCount;

        Field(int usagePage, int usage, int[] usages, int usageMin, int usageMax,
              int logicalMin, int logicalMax, int reportId, int reportSize, int reportCount) {
            this.usagePage = usagePage;
            this.usage = usage;
            this.usages = usages != null ? usages : new int[0];
            this.usageMin = usageMin;
            this.usageMax = usageMax;
            this.logicalMin = logicalMin;
            this.logicalMax = logicalMax;
            this.reportId = reportId;
            this.reportSize = reportSize;
            this.reportCount = reportCount;
        }

        /** 本主项是否包含某用途（含单 Usage 与 Usage 列表两种声明）。 */
        public boolean hasUsage(int u) {
            for (int x : usages) {
                if (x == u) return true;
            }
            return false;
        }

        /** Usage Minimum/Maximum 数组声明（Contact Count 这类），不是坐标轴。 */
        public boolean isUsageRange() {
            return usageMin != 0 || usageMax != 0;
        }

        @Override
        public String toString() {
            return "Field{page=0x" + Integer.toHexString(usagePage)
                    + ", usage=0x" + Integer.toHexString(usage)
                    + ", logicalMax=" + logicalMax
                    + ", size=" + reportSize + "×" + reportCount + "}";
        }
    }

    /** 解析所有 Input 主项。描述符损坏时返回已解析到的部分。 */
    public static List<Field> parseInputs(byte[] d) {
        List<Field> out = new ArrayList<Field>();
        if (d == null) return out;

        int usagePage = 0, logicalMin = 0, logicalMax = 0, reportSize = 0, reportId = 0, reportCount = 0;
        int usage = 0, usageMin = 0, usageMax = 0;
        List<Integer> usageList = new ArrayList<Integer>();
        int pos = 0;

        while (pos < d.length) {
            int prefix = d[pos] & 0xFF;
            // 前缀 = bTag(4) | bType(2) | bSize(2)；下面的 tag 就是高 4 位。
            // （本文件里的 USAGE_PAGE / LOGICAL_MIN … 常量本身就是完整前缀字节，
            //  所以 `>> 4` 得到的就是对应的 tag。）
            int sizeCode = prefix & 0x03;
            int size = sizeCode == 3 ? 4 : sizeCode;
            int type = (prefix >> 2) & 0x03;
            int tag = (prefix >> 4) & 0x0F;
            pos++;

            long value = 0;
            for (int i = 0; i < size && pos < d.length; i++) {
                value |= (long) (d[pos++] & 0xFF) << (8 * i);
            }
            // 按符号扩展（逻辑最小值可能是负数）
            int sval = (int) value;
            if (size > 0 && size < 4 && (value & (1L << (size * 8 - 1))) != 0) {
                sval = (int) (value | (~0L << (size * 8)));
            }

            if (type == TYPE_GLOBAL) {
                switch (tag) {
                    case (USAGE_PAGE >> 4): usagePage = (int) value; break;
                    case (LOGICAL_MIN >> 4): logicalMin = sval; break;
                    case (LOGICAL_MAX >> 4): logicalMax = sval; break;
                    case (REPORT_SIZE >> 4): reportSize = (int) value; break;
                    case (REPORT_ID >> 4): reportId = (int) value; break;
                    case (REPORT_COUNT >> 4): reportCount = (int) value; break;
                    default: break;
                }
            } else if (type == TYPE_LOCAL) {
                switch (tag) {
                    case (USAGE >> 4):
                        usage = (int) value;
                        if (!usageList.contains((int) value)) usageList.add((int) value);
                        break;
                    case (USAGE_MIN >> 4): usageMin = (int) value; break;
                    case (USAGE_MAX >> 4): usageMax = (int) value; break;
                    default: break;
                }
            } else if (type == TYPE_MAIN) {
                if (tag == (INPUT >> 4)) {
                    int[] us = new int[usageList.size()];
                    for (int i = 0; i < us.length; i++) us[i] = usageList.get(i);
                    out.add(new Field(usagePage, usage, us, usageMin, usageMax,
                            logicalMin, logicalMax, reportId, reportSize, reportCount));
                }
                // Local 作用域在主项后清空
                usage = usageMin = usageMax = 0;
                usageList.clear();
            }
        }
        return out;
    }

    /** 取某个 Usage Page 下所有 Input 字段的**去重**逻辑上限（升序）。 */
    public static List<Integer> logicalMaxima(byte[] descriptor, int usagePage) {
        Set<Integer> seen = new LinkedHashSet<Integer>();
        for (Field f : parseInputs(descriptor)) {
            if (f.usagePage == usagePage && f.logicalMax > 0) seen.add(f.logicalMax);
        }
        return new ArrayList<Integer>(seen);
    }

    /**
     * 触摸屏 Input 字段的 Report ID；描述符里没有 Report ID 时为 0。
     *
     * <p>声明了 Report ID 的 HID 设备，**每个报告的第一个字节都是 ID**，真正的
     * 触摸数据从第 2 字节开始。不剥掉这一字节，坐标会整体错位（X 高字节被当成
     * 触点 ID），现场表现是「能点但位置偏移」。要不要剥由发送方决定：
     * 只有报告首字节确实等于这个 ID 时才剥，兼容「声明了但实际没带」的实现。
     */
    public static int touchReportId(byte[] descriptor) {
        for (Field f : parseInputs(descriptor)) {
            if ((f.hasUsage(0x30) || f.hasUsage(0x31)) && f.reportId > 0) return f.reportId;
        }
        return 0;
    }

    /**
     * 触摸屏的 (maxX, maxY) 逻辑上限。
     *
     * <p>判定按 usage 列表而不是单 usage（「Usage(X), Usage(Y), Input」一条主项
     * 带两轴是常见写法）；Usage 数组声明（Contact Count 之类）不参与轴上限兜底，
     * 否则它的小上限（如 10）会被当成轴的逻辑上限。
     */
    public static int[] touchAxisMaxima(byte[] descriptor) {
        List<Field> fields = parseInputs(descriptor);
        Integer x = null;
        Integer y = null;
        Set<Integer> others = new LinkedHashSet<Integer>();
        for (Field f : fields) {
            if (f.logicalMax <= 1) continue;   // Tip Switch、常量填充等
            if (f.hasUsage(0x30)) {
                x = f.logicalMax;
            } else if (f.hasUsage(0x31)) {
                y = f.logicalMax;
            } else if (!f.isUsageRange()) {
                others.add(f.logicalMax);
            }
        }
        if (x != null && y != null) return new int[]{x, y};
        if (x != null) return new int[]{x, x};
        if (y != null) return new int[]{y, y};

        if (others.isEmpty()) return null;
        List<Integer> sorted = new ArrayList<Integer>(others);
        java.util.Collections.sort(sorted);
        if (sorted.size() == 1) {
            int m = sorted.get(0);
            return new int[]{m, m};
        }
        return new int[]{sorted.get(sorted.size() - 2), sorted.get(sorted.size() - 1)};
    }
}
