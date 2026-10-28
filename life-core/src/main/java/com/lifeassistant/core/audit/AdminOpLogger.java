package com.lifeassistant.core.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 记录管理员的操作，事后可追查。 */
@Component
@RequiredArgsConstructor
public class AdminOpLogger {

    private final AdminOpLogMapper mapper;

    /** 写一条管理员操作日志，detail 暂不填。 */
    public void log(long adminId, String action, String target) {
        AdminOpLog row = new AdminOpLog();
        row.setAdminId(adminId);
        row.setAction(action);
        row.setTarget(target);
        mapper.insert(row);
    }
}
