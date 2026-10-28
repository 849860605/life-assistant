package com.lifeassistant.core.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.crypto.FieldCipher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 通知发送入口：管理用户的推送渠道（配置加密存库），并向所有已启用渠道推送、逐条记录结果。 */
@Service
public class NotificationService {

    private final NotifyChannelMapper channelMapper;
    private final NotificationRecordMapper recordMapper;
    private final FieldCipher cipher;
    private final ObjectMapper objectMapper;
    private final Map<String, Notifier> notifiers;

    /** 按 Notifier.type() 建立渠道类型到实现的索引。 */
    public NotificationService(NotifyChannelMapper channelMapper, NotificationRecordMapper recordMapper,
                               FieldCipher cipher, ObjectMapper objectMapper, List<Notifier> notifiers) {
        this.channelMapper = channelMapper;
        this.recordMapper = recordMapper;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.notifiers = notifiers.stream().collect(Collectors.toMap(Notifier::type, Function.identity()));
    }

    /** 设置或更新用户的 Bark 设备 key：配置 JSON 加密后存库，并把该渠道重新置为启用。 */
    public void configureBark(long userId, String deviceKey) {
        String config;
        try {
            config = objectMapper.writeValueAsString(Map.of("deviceKey", deviceKey.trim()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        NotifyChannel channel = channelMapper.selectOne(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getUserId, userId)
                .eq(NotifyChannel::getType, BarkNotifier.TYPE));
        if (channel == null) {
            channel = new NotifyChannel();
            channel.setUserId(userId);
            channel.setType(BarkNotifier.TYPE);
            channel.setEnabled(true);
            channel.setConfigEnc(cipher.encrypt(config));
            channelMapper.insert(channel);
        } else {
            channel.setConfigEnc(cipher.encrypt(config));
            channel.setEnabled(true);
            channelMapper.updateById(channel);
        }
    }

    /** 用户是否至少有一个已启用的推送渠道。 */
    public boolean hasChannel(long userId) {
        return channelMapper.selectCount(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getUserId, userId)
                .eq(NotifyChannel::getEnabled, true)) > 0;
    }

    /**
     * 推送到用户所有已启用的渠道，每个渠道各写一条推送记录。
     * 有一个成功就算成功；全部失败时抛出最后一个异常，由调用方决定是否重试。
     */
    public void send(long userId, Notice notice, Long reminderId) {
        List<NotifyChannel> channels = channelMapper.selectList(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getUserId, userId)
                .eq(NotifyChannel::getEnabled, true));
        if (channels.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "还没有设置推送渠道，请先在\"我的\"里填写 Bark 设备 key");
        }
        RuntimeException lastError = null;
        int succeeded = 0;
        for (NotifyChannel channel : channels) {
            Notifier notifier = notifiers.get(channel.getType());
            NotificationRecord record = new NotificationRecord();
            record.setUserId(userId);
            record.setChannel(channel.getType());
            record.setTitle(notice.title());
            record.setReminderId(reminderId);
            try {
                if (notifier == null) {
                    throw new BizException(ErrorCode.INTERNAL, "不支持的推送渠道：" + channel.getType());
                }
                notifier.send(cipher.decrypt(channel.getConfigEnc()), notice);
                record.setSuccess(true);
                succeeded++;
            } catch (RuntimeException e) {
                record.setSuccess(false);
                record.setError(e.getMessage());
                lastError = e;
            }
            recordMapper.insert(record);
        }
        if (succeeded == 0 && lastError != null) {
            throw lastError;
        }
    }
}
