package com.carbridge.carplay.pairing;

/**
 * 配对身份的持久化抽象。
 */
public interface IdentityStore {

    /** 读取已保存的身份；没有返回 null。 */
    Ed25519.KeyPair load();

    /** 保存身份（覆盖）。 */
    void save(Ed25519.KeyPair identity);

    /** 清除身份（用户主动"取消配对"时用）。 */
    void clear();

    /** 不持久化的实现（每次调用返回 null，用于测试）。 */
    IdentityStore NONE = new IdentityStore() {
        @Override public Ed25519.KeyPair load() {
            return null;
        }

        @Override public void save(Ed25519.KeyPair identity) {
        }

        @Override public void clear() {
        }
    };
}
