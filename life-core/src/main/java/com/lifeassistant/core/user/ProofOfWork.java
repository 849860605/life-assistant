package com.lifeassistant.core.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 工作量证明的计算部分（纯函数）：SHA-256(题目 + ":" + nonce) 开头至少有 difficulty 个 0 比特才算解出来。
 * 浏览器里算一道 16 位难度的题平均要算六万多次哈希，正常用户等一两秒，批量刷验证码的成本就上去了。
 */
public final class ProofOfWork {

    private ProofOfWork() {
    }

    /** nonce 必须是 1~20 位十进制数字，前端按这个格式算。 */
    public static boolean validNonce(String nonce) {
        return nonce != null && nonce.matches("\\d{1,20}");
    }

    /** 这个 nonce 解出了这道题没有。 */
    public static boolean solved(String token, String nonce, int difficulty) {
        if (token == null || !validNonce(nonce)) {
            return false;
        }
        return leadingZeroBits(sha256(token + ":" + nonce)) >= difficulty;
    }

    /** 从头开始试 nonce，直到解出来（测试和自检用；正式环境是浏览器在算）。 */
    public static String solve(String token, int difficulty) {
        for (long n = 0; ; n++) {
            String nonce = Long.toString(n);
            if (solved(token, nonce, difficulty)) {
                return nonce;
            }
        }
    }

    /** 字节数组开头连续有几个 0 比特。 */
    static int leadingZeroBits(byte[] bytes) {
        int bits = 0;
        for (byte b : bytes) {
            if (b == 0) {
                bits += 8;
                continue;
            }
            return bits + Integer.numberOfLeadingZeros(b & 0xff) - 24;
        }
        return bits;
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
