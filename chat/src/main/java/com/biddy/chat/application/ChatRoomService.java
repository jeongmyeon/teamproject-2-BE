package com.biddy.chat.application;

import com.biddy.chat.domain.model.ChatMessage;
import com.biddy.chat.domain.model.ChatRoom;
import com.biddy.chat.domain.repository.ChatMessageRepository;
import com.biddy.chat.domain.repository.ChatRoomRepository;
import com.biddy.chat.presentation.dto.ChatMessageResponse;
import com.biddy.chat.presentation.dto.ChatRoomRequest;
import com.biddy.chat.presentation.dto.ChatRoomResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatRoomResponse createOrGetRoom(ChatRoomRequest request) {
        ChatRoom chatRoom = chatRoomRepository.findByProductIdAndBuyerId(request.getProductId(), request.getBuyerId())
                .orElseGet(() -> chatRoomRepository.save(
                        ChatRoom.builder()
                                .productId(request.getProductId())
                                .buyerId(request.getBuyerId())
                                .sellerId(request.getSellerId())
                                .build()
                ));

        return ChatRoomResponse.builder()
                .id(chatRoom.getId())
                .productId(chatRoom.getProductId())
                .buyerId(chatRoom.getBuyerId())
                .sellerId(chatRoom.getSellerId())
                .build();
    }

    @Transactional
    public List<ChatMessageResponse> getMessages(Long roomId, Long memberId, Long lastMessageId, int size) {
        PageRequest pageRequest = PageRequest.of(0, size);
        List<ChatMessage> messages;
        
        // 메시지를 읽어갈 때 안 읽은 메시지 읽음 처리 (내 memberId가 수신자인 경우)
        chatMessageRepository.markMessagesAsRead(roomId, memberId);
        
        if (lastMessageId == null) {
            messages = chatMessageRepository.findByRoomIdOrderByIdDesc(roomId, pageRequest);
        } else {
            messages = chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, lastMessageId, pageRequest);
        }

        return messages.stream()
                .map(ChatMessageResponse::from)
                .collect(Collectors.toList());
    }
    @Transactional(readOnly = true)
    public List<ChatRoomResponse> getMyRooms(Long memberId) {
        return chatRoomRepository.findByBuyerIdOrSellerId(memberId, memberId).stream()
                .map(room -> {
                    // 가장 최근 메시지 조회
                    ChatMessage lastMessage = chatMessageRepository.findTopByRoomIdOrderByIdDesc(room.getId()).orElse(null);
                    // 안 읽은 메시지 개수 조회
                    long unreadCount = chatMessageRepository.countByRoomIdAndSenderIdNotAndIsReadFalse(room.getId(), memberId);
                    
                    return ChatRoomResponse.builder()
                            .id(room.getId())
                            .productId(room.getProductId())
                            .buyerId(room.getBuyerId())
                            .sellerId(room.getSellerId())
                            .lastMessage(lastMessage != null ? lastMessage.getContent() : null)
                            .lastMessageAt(lastMessage != null ? lastMessage.getCreatedAt() : null)
                            .unreadCount(unreadCount)
                            .build();
                })
                .collect(Collectors.toList());
    }
    @Transactional
    public void markAsRead(Long roomId, Long memberId) {
        chatMessageRepository.markMessagesAsRead(roomId, memberId);
    }
}
