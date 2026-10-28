package com.lifeassistant.core.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** t_chat_message 的 Mapper。 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    /** 取会话最近 limit 条消息，按时间正序返回。 */
    @Select("""
            SELECT * FROM (
                SELECT * FROM t_chat_message WHERE conversation_id = #{conversationId}
                ORDER BY id DESC LIMIT #{limit}
            ) t ORDER BY id
            """)
    List<ChatMessageEntity> selectRecent(String conversationId, int limit);
}
