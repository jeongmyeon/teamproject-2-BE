package com.biddy.chat.presentation;

import com.biddy.chat.application.ChatService;
import com.biddy.chat.presentation.dto.ChatMessageRequest;
import com.biddy.chat.presentation.dto.ChatReadRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class StompChatController {

    private final ChatService chatService;

    // Client sends messages to /app/chat.send
    @MessageMapping("/chat.send")
    public void sendMessage(ChatMessageRequest request, Authentication authentication) {
        Long senderId = (Long) authentication.getPrincipal();
        log.info("Received message from user {} to room {}: {}", senderId, request.getRoomId(), request.getContent());
        
        chatService.saveAndPublishMessage(request, senderId);
    }

    // Client sends read acknowledgment to /app/chat.read
    @MessageMapping("/chat.read")
    public void readMessages(ChatReadRequest request, Authentication authentication) {
        Long memberId = (Long) authentication.getPrincipal();
        log.info("User {} read messages in room {}", memberId, request.getRoomId());
        
        chatService.markAsRead(request.getRoomId(), memberId);
    }
}
