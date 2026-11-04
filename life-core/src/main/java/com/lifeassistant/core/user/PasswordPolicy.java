package com.lifeassistant.core.user;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;

/** 密码规则：8 到 64 位，同时有字母和数字，前后不能有空格；后台新建账号、改密码、重置密码都按这个校验。 */
public final class PasswordPolicy {

    static final int MIN = 8;
    static final int MAX = 64;

    private PasswordPolicy() {
    }

    /** 不符合规则抛 BAD_REQUEST，提示具体哪里不对。 */
    public static void check(String raw) {
        if (raw == null || raw.length() < MIN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 " + MIN + " 位");
        }
        if (raw.length() > MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码最多 " + MAX + " 位");
        }
        if (!raw.equals(raw.strip())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码前后不能有空格");
        }
        if (raw.chars().noneMatch(Character::isLetter) || raw.chars().noneMatch(Character::isDigit)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码要同时包含字母和数字");
        }
    }
}
