package com.biddy.chat.presentation.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRoomRequest {
    private Long productId;
    private Long buyerId;
    private Long sellerId;
}
