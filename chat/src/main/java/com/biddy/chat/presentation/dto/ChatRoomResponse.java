package com.biddy.chat.presentation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

import lombok.Builder;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRoomResponse {
    private Long id;
    private Long productId;
    private Long buyerId;
    private Long sellerId;
    private String lastMessage;
    private LocalDateTime lastMessageAt;
    private long unreadCount;
}
