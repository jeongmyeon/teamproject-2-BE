package com.biddy.chat.infrastructure.redis;

import com.biddy.chat.presentation.dto.ChatMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessageSendingOperations messagingTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // Deserialize published redis message
            String publishMessage = redisTemplate.getStringSerializer().deserialize(message.getBody());
            ChatMessageResponse roomMessage = objectMapper.readValue(publishMessage, ChatMessageResponse.class);
            
            // Broadcast the message via WebSocket to all subscribers in the room
            messagingTemplate.convertAndSend("/topic/room/" + roomMessage.getRoomId(), roomMessage);
            
        } catch (Exception e) {
            log.error("Error processing received redis message", e);
        }
    }
}
