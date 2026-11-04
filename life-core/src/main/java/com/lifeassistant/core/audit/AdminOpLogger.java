package com.lifeassistant.core.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 记录管理员的操作，事后可追查。 */
@Component
@RequiredArgsConstructor
public class AdminOpLogger {

    private final AdminOpLogMapper mapper;

    /** 写一条管理员操作日志（不记 IP），detail 暂不填。 */
    public void log(long adminId, String action, String target) {
        log(adminId, action, target, null);
    }

    /** 写一条管理员操作日志，带操作时的 IP。 */
    public void log(long adminId, String action, String target, String ip) {
        AdminOpLog row = new AdminOpLog();
        row.setAdminId(adminId);
        row.setAction(action);
        row.setTarget(target);
        row.setIp(ip);
        mapper.insert(row);
    }
}
