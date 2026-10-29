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
        public final int usage;
        public final int usageMin;
        public final int usageMax;
        public final int logicalMin;
        public final int logicalMax;
        public final int reportId;
        public final int reportSize;
        public final int reportCount;

        Field(int usagePage, int usage, int usageMin, int usageMax,
              int logicalMin, int logicalMax, int reportId, int reportSize, int reportCount) {
            this.usagePage = usagePage;
            this.usage = usage;
            this.usageMin = usageMin;
            this.usageMax = usageMax;
            this.logicalMin = logicalMin;
            this.logicalMax = logicalMax;
            this.reportId = reportId;
            this.reportSize = reportSize;
            this.reportCount = reportCount;
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
                    case (USAGE >> 4): usage = (int) value; break;
                    case (USAGE_MIN >> 4): usageMin = (int) value; break;
                    case (USAGE_MAX >> 4): usageMax = (int) value; break;
                    default: break;
                }
            } else if (type == TYPE_MAIN) {
                if (tag == (INPUT >> 4)) {
                    out.add(new Field(usagePage, usage, usageMin, usageMax,
                            logicalMin, logicalMax, reportId, reportSize, reportCount));
                }
                // Local 作用域在主项后清空
                usage = usageMin = usageMax = 0;
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
     * 触摸屏的 (maxX, maxY) 逻辑上限。
     */
    public static int[] touchAxisMaxima(byte[] descriptor) {
        List<Field> fields = parseInputs(descriptor);
        Integer x = null;
        Integer y = null;
        Set<Integer> others = new LinkedHashSet<Integer>();
        for (Field f : fields) {
            if (f.logicalMax <= 1) continue;   // Tip Switch、常量填充等
            if (f.usage == 0x30) {
                x = f.logicalMax;
            } else if (f.usage == 0x31) {
                y = f.logicalMax;
            } else {
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
