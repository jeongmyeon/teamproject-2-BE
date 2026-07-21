package com.biddy.auction.watch.presentation;

import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import com.biddy.auction.watch.application.dto.ToggleWatchResult;
import com.biddy.auction.watch.application.usecase.WatchUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WatchController.class)
@AutoConfigureMockMvc(addFilters = false)
class WatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WatchUseCase watchUseCase;

    @Test
    @DisplayName("관심 등록하면 watching=true와 갱신된 watcherCount를 반환한다")
    void toggleWatch_register() throws Exception {
        given(watchUseCase.toggleWatch("A-001", 42L))
                .willReturn(new ToggleWatchResult(true, 89));

        mockMvc.perform(post("/api/v1/auctions/A-001/watch")
                        .header("X-Member-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(true))
                .andExpect(jsonPath("$.watcherCount").value(89));
    }

    @Test
    @DisplayName("관심 해제하면 watching=false와 갱신된 watcherCount를 반환한다")
    void toggleWatch_unregister() throws Exception {
        given(watchUseCase.toggleWatch("A-001", 42L))
                .willReturn(new ToggleWatchResult(false, 87));

        mockMvc.perform(post("/api/v1/auctions/A-001/watch")
                        .header("X-Member-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(false))
                .andExpect(jsonPath("$.watcherCount").value(87));
    }

    @Test
    @DisplayName("존재하지 않는 경매에 토글하면 404를 반환한다")
    void toggleWatch_notFound() throws Exception {
        given(watchUseCase.toggleWatch("A-999", 42L))
                .willThrow(new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        mockMvc.perform(post("/api/v1/auctions/A-999/watch")
                        .header("X-Member-Id", "42"))
                .andExpect(status().isNotFound());
    }
}
