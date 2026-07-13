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

        return new ChatRoomResponse(chatRoom.getId(), chatRoom.getProductId(), chatRoom.getBuyerId(), chatRoom.getSellerId());
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(Long roomId, Long lastMessageId, int size) {
        PageRequest pageRequest = PageRequest.of(0, size);
        List<ChatMessage> messages;
        
        if (lastMessageId == null) {
            messages = chatMessageRepository.findByRoomIdOrderByIdDesc(roomId, pageRequest);
        } else {
            messages = chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, lastMessageId, pageRequest);
        }

        return messages.stream()
                .map(ChatMessageResponse::from)
                .collect(Collectors.toList());
    }
}
