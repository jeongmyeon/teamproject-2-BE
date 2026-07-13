package com.biddy.chat.application;

import com.biddy.chat.domain.model.ChatMessage;
import com.biddy.chat.domain.repository.ChatMessageRepository;
import com.biddy.chat.infrastructure.redis.RedisPublisher;
import com.biddy.chat.presentation.dto.ChatMessageRequest;
import com.biddy.chat.presentation.dto.ChatMessageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final RedisPublisher redisPublisher;
    
    // Redis Topic for all chat messages (for simplicity, we can use a single topic or one topic per room)
    // Using a single topic for now, the subscriber will broadcast to the correct WebSocket topic
    private final ChannelTopic chatTopic = new ChannelTopic("chatRoomTopic");

    @Transactional
    public void saveAndPublishMessage(ChatMessageRequest request, Long senderId) {
        // 1. Save to DB
        ChatMessage message = ChatMessage.builder()
                .roomId(request.getRoomId())
                .senderId(senderId)
                .content(request.getContent())
                .build();
        
        ChatMessage savedMessage = chatMessageRepository.save(message);

        // 2. Publish to Redis
        ChatMessageResponse response = ChatMessageResponse.from(savedMessage);
        redisPublisher.publish(chatTopic, response);
    }
}
