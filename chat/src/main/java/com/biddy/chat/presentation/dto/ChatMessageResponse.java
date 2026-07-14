package com.biddy.chat.presentation.dto;

import com.biddy.chat.domain.model.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {
    private Long id;
    private Long roomId;
    private Long senderId;
    private String content;
    private LocalDateTime createdAt;
    
    // Enum to distinguish normal message and system message (Enter/Leave)
    private MessageType type; 

    public enum MessageType {
        CHAT, ENTER, LEAVE
    }

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRoomId(),
                message.getSenderId(),
                message.getContent(),
                message.getCreatedAt(),
                MessageType.CHAT
        );
    }
}
