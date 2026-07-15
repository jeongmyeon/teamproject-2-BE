package com.biddy.chat.domain.repository;

import com.biddy.chat.domain.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    
    // cursor based pagination
    @Query("SELECT m FROM ChatMessage m WHERE m.roomId = :roomId AND m.id < :lastMessageId ORDER BY m.id DESC")
    List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(
            @Param("roomId") Long roomId, 
            @Param("lastMessageId") Long lastMessageId, 
            Pageable pageable);

    @Query("SELECT m FROM ChatMessage m WHERE m.roomId = :roomId ORDER BY m.id DESC")
    List<ChatMessage> findByRoomIdOrderByIdDesc(
            @Param("roomId") Long roomId, 
            Pageable pageable);

    // 가장 최근 메시지 1개 조회
    Optional<ChatMessage> findTopByRoomIdOrderByIdDesc(Long roomId);

    // 안 읽은 메시지 개수 카운트 (상대방이 보낸 메시지 중 isRead = false)
    long countByRoomIdAndSenderIdNotAndIsReadFalse(Long roomId, Long memberId);

    // 읽음 처리 업데이트 (상대방이 보낸 메시지만 isRead = true로 변경)
    @Modifying
    @Query("UPDATE ChatMessage m SET m.isRead = true WHERE m.roomId = :roomId AND m.senderId != :memberId AND m.isRead = false")
    void markMessagesAsRead(@Param("roomId") Long roomId, @Param("memberId") Long memberId);
}
