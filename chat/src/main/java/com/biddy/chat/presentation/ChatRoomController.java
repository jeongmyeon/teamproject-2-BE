package com.biddy.chat.presentation;

import com.biddy.chat.application.ChatRoomService;
import com.biddy.chat.presentation.dto.ChatMessageResponse;
import com.biddy.chat.presentation.dto.ChatRoomRequest;
import com.biddy.chat.presentation.dto.ChatRoomResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @PostMapping("/rooms")
    public ResponseEntity<ChatRoomResponse> createOrGetRoom(
            @RequestHeader("X-Member-Id") Long memberId,
            @RequestBody ChatRoomRequest request) {
        request.setBuyerId(memberId);
        return ResponseEntity.ok(chatRoomService.createOrGetRoom(request));
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoomResponse>> getMyRooms(@RequestHeader("X-Member-Id") Long memberId) {
        return ResponseEntity.ok(chatRoomService.getMyRooms(memberId));
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long lastMessageId,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(chatRoomService.getMessages(roomId, lastMessageId, size));
    }
}
