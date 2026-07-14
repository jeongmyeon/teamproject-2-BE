package com.biddy.chat.domain.repository;

import com.biddy.chat.domain.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
