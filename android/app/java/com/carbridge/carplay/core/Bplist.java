package com.carbridge.carplay.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小二进制 plist（`bplist00`）编解码。
 */
public final class Bplist {

    /**
     * 显式的无符号 64 位整数。
     *
     * <p>用途：CarPlay 的 {@code features} 位图 —— 它是 64 位无符号的位集合，
     * 最高位（bit31 起的 MediaControlV2 等）一旦按普通整数用最小宽度写，
     * 对端按有符号读会符号扩展，高 32 位全变 1。
     */
    public static final class U64 {
        public final long value;

        public U64(long value) {
            this.value = value;
        }
    }

    private Bplist() {
    }

    // ================================================================== 写

    public static byte[] write(Object root) {
        return new Writer().encode(root);
    }

    private static final class Writer {
        private final List<Object> objects = new ArrayList<Object>();
        private final IdentityHashMap<Object, Integer> containerIds = new IdentityHashMap<Object, Integer>();
        private final Map<Object, Integer> primitiveIds = new LinkedHashMap<Object, Integer>();

        byte[] encode(Object root) {
            int topId = register(root);

            // 广度优先：登记所有容器的子节点
            for (int i = 0; i < objects.size(); i++) {
                Object o = objects.get(i);
                if (o instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                        register(e.getKey());
                        register(e.getValue());
                    }
                } else if (o instanceof List) {
                    for (Object c : (List<?>) o) {
                        register(c);
                    }
                }
            }

            int numObjects = objects.size();
            int objectRefSize = byteWidth(numObjects - 1);

            final int base = 8; // "bplist00"
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int[] offsets = new int[numObjects];
            for (int i = 0; i < numObjects; i++) {
                offsets[i] = base + body.size();
                byte[] enc = encodeObject(objects.get(i), objectRefSize);
                body.write(enc, 0, enc.length);
            }

            int offsetTableOffset = base + body.size();
            int offsetIntSize = byteWidth(offsetTableOffset + numObjects * 4);
            for (int off : offsets) {
                writeInt(body, off, offsetIntSize);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write('b');
            out.write('p');
            out.write('l');
            out.write('i');
            out.write('s');
            out.write('t');
            out.write('0');
            out.write('0');
            try {
                body.writeTo(out);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }

            // trailer: 5 unused + sortVersion + offsetIntSize + objectRefSize
            //          + numObjects(8) + topObject(8) + offsetTableOffset(8)
            for (int i = 0; i < 5; i++) out.write(0);
            out.write(0);
            out.write(offsetIntSize);
            out.write(objectRefSize);
            writeInt(out, numObjects, 8);
            writeInt(out, topId, 8);
            writeInt(out, offsetTableOffset, 8);

            return out.toByteArray();
        }

        private int register(Object o) {
            if (isPrimitive(o)) {
                Integer existing = primitiveIds.get(o);
                if (existing != null) return existing;
                int id = objects.size();
                objects.add(o);
                primitiveIds.put(o, id);
                return id;
            }
            Integer existing = containerIds.get(o);
            if (existing != null) return existing;
            int id = objects.size();
            objects.add(o);
            containerIds.put(o, id);
            return id;
        }

        private byte[] encodeObject(Object o, int objectRefSize) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (o == null) {
                b.write(0x00);
            } else if (o instanceof Boolean) {
                b.write(((Boolean) o) ? 0x09 : 0x08);
            } else if (o instanceof U64) {
                // ⚠️ 显式的 **无符号 64 位**：bplist 的普通整数是「有符号 + 最小宽度」，
                //    像 0x00000000BF7F4EDD 这种（bit31 置位）若按 4 字节写，
                //    对端按 int32 读会**符号扩展**成 0xFFFFFFFFBF7F4EDD → 高 32 位全 1。
                //    CarPlay 的 features 就是 64 位位图，必须写满 8 字节。
                long v = ((U64) o).value;
                b.write(0x13);   // integer, 8 bytes
                writeInt(b, v, 8);
            } else if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
                long v = ((Number) o).longValue();
                int size = byteWidth(v);
                b.write(0x10 | log2(size));
                writeInt(b, v, size);
            } else if (o instanceof Double || o instanceof Float) {
                long bits = Double.doubleToLongBits(((Number) o).doubleValue());
                b.write(0x23); // real, 8 bytes
                writeInt(b, bits, 8);
            } else if (o instanceof String) {
                String s = (String) o;
                if (isAscii(s)) {
                    byte[] raw = asciiBytes(s);
                    writeHeader(b, 0x50, raw.length);
                    b.write(raw, 0, raw.length);
                } else {
                    byte[] raw = utf16BeBytes(s);
                    writeHeader(b, 0x60, s.length());
                    b.write(raw, 0, raw.length);
                }
            } else if (o instanceof byte[]) {
                byte[] raw = (byte[]) o;
                writeHeader(b, 0x40, raw.length);
                b.write(raw, 0, raw.length);
            } else if (o instanceof List) {
                List<?> list = (List<?>) o;
                writeHeader(b, 0xA0, list.size());
                for (Object c : list) writeInt(b, register(c), objectRefSize);
            } else if (o instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) o;
                writeHeader(b, 0xD0, map.size());
                for (Object k : map.keySet()) writeInt(b, register(k), objectRefSize);
                for (Object v : map.values()) writeInt(b, register(v), objectRefSize);
            } else {
                throw new IllegalArgumentException("不支持的类型: " + o.getClass());
            }
            return b.toByteArray();
        }

        /** 写 type 后 4 bit 的 size；size>=15 时用 0xF + 内联整型对象。 */
        private void writeHeader(ByteArrayOutputStream b, int typeBase, int size) {
            if (size < 15) {
                b.write(typeBase | size);
            } else {
                b.write(typeBase | 0x0F);
                int w = byteWidth(size);
                b.write(0x10 | log2(w));
                writeInt(b, size, w);
            }
        }
    }

    // ================================================================== 读

    public static Object read(byte[] data) {
        if (data.length < 8 + 32 || data[0] != 'b' || data[1] != 'p' || data[2] != 'l'
                || data[3] != 'i' || data[4] != 's' || data[5] != 't') {
            throw new IllegalArgumentException("不是二进制 plist");
        }
        int trailer = data.length - 32;
        int offsetIntSize = data[trailer + 6] & 0xFF;
        int objectRefSize = data[trailer + 7] & 0xFF;
        long numObjects = readInt(data, trailer + 8, 8);
        long topObject = readInt(data, trailer + 16, 8);
        long offsetTableOffset = readInt(data, trailer + 24, 8);

        long[] offsets = new long[(int) numObjects];
        for (int i = 0; i < numObjects; i++) {
            offsets[i] = readInt(data, (int) offsetTableOffset + i * offsetIntSize, offsetIntSize);
        }
        return readObject(data, offsets, (int) topObject, objectRefSize, 0);
    }

    private static Object readObject(byte[] d, long[] offsets, int index, int refSize, int depth) {
        if (depth > 64) throw new IllegalArgumentException("plist 嵌套过深");
        int pos = (int) offsets[index];
        int marker = d[pos] & 0xFF;
        int type = marker & 0xF0;
        int nibble = marker & 0x0F;
        int[] cursor = new int[]{pos + 1};

        switch (type) {
            case 0x00:
                if (nibble == 0x08) return Boolean.FALSE;
                if (nibble == 0x09) return Boolean.TRUE;
                return null;
            case 0x10: {
                int size = 1 << nibble;
                return readInt(d, cursor[0], size);
            }
            case 0x20: {
                int size = 1 << nibble;
                long bits = readInt(d, cursor[0], size);
                return Double.longBitsToDouble(size == 4 ? (bits & 0xFFFFFFFFL) << 32 | 0 : bits);
            }
            case 0x40: {
                int size = readSize(d, nibble, cursor);
                byte[] raw = new byte[size];
                System.arraycopy(d, cursor[0], raw, 0, size);
                return raw;
            }
            case 0x50: {
                int size = readSize(d, nibble, cursor);
                return new String(d, cursor[0], size, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            case 0x60: {
                int size = readSize(d, nibble, cursor);
                return new String(d, cursor[0], size * 2, java.nio.charset.StandardCharsets.UTF_16BE);
            }
            case 0xA0: {
                int size = readSize(d, nibble, cursor);
                List<Object> list = new ArrayList<Object>(size);
                for (int i = 0; i < size; i++) {
                    int ref = (int) readInt(d, cursor[0] + i * refSize, refSize);
                    list.add(readObject(d, offsets, ref, refSize, depth + 1));
                }
                return list;
            }
            case 0xD0: {
                int size = readSize(d, nibble, cursor);
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                for (int i = 0; i < size; i++) {
                    int keyRef = (int) readInt(d, cursor[0] + i * refSize, refSize);
                    int valRef = (int) readInt(d, cursor[0] + (size + i) * refSize, refSize);
                    Object key = readObject(d, offsets, keyRef, refSize, depth + 1);
                    Object val = readObject(d, offsets, valRef, refSize, depth + 1);
                    map.put(String.valueOf(key), val);
                }
                return map;
            }
            default:
                throw new IllegalArgumentException("未知 plist 类型 0x" + Integer.toHexString(type));
        }
    }

    private static int readSize(byte[] d, int nibble, int[] cursor) {
        if (nibble < 15) return nibble;
        int marker = d[cursor[0]] & 0xFF;
        if ((marker & 0xF0) != 0x10) throw new IllegalArgumentException("长度字段不是整型");
        int size = 1 << (marker & 0x0F);
        int v = (int) readInt(d, cursor[0] + 1, size);
        cursor[0] += 1 + size;
        return v;
    }

    // ================================================================== 工具

    private static boolean isPrimitive(Object o) {
        return o == null || o instanceof String || o instanceof Number || o instanceof Boolean
                || o instanceof Character;
    }

    private static int byteWidth(long v) {
        if (v < 0) return 8;
        if (v < 0x100L) return 1;
        if (v < 0x10000L) return 2;
        if (v < 0x100000000L) return 4;
        return 8;
    }

    private static int log2(int size) {
        switch (size) {
            case 1: return 0;
            case 2: return 1;
            case 4: return 2;
            case 8: return 3;
            default: throw new IllegalArgumentException("非法宽度 " + size);
        }
    }

    private static void writeInt(ByteArrayOutputStream b, long v, int size) {
        for (int i = size - 1; i >= 0; i--) {
            b.write((int) ((v >>> (8 * i)) & 0xFF));
        }
    }

    private static long readInt(byte[] d, int off, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            v = (v << 8) | (d[off + i] & 0xFFL);
        }
        return v;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    private static byte[] asciiBytes(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = (byte) s.charAt(i);
        return out;
    }

    private static byte[] utf16BeBytes(String s) {
        byte[] out = new byte[s.length() * 2];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[i * 2] = (byte) (c >>> 8);
            out[i * 2 + 1] = (byte) c;
        }
        return out;
    }
}
