package com.biddy.memberservice.application.service;

import com.biddy.memberservice.application.dto.request.LoginRequest;
import com.biddy.memberservice.application.dto.response.TokenResponse;
import com.biddy.memberservice.application.event.MemberEventPublisher;
import com.biddy.memberservice.domain.enums.MemberRole;
import com.biddy.memberservice.domain.enums.MemberStatus;
import com.biddy.memberservice.domain.exception.RefreshTokenReuseException;
import com.biddy.memberservice.domain.model.Member;
import com.biddy.memberservice.domain.model.RefreshToken;
import com.biddy.memberservice.domain.repository.EmailVerificationRepository;
import com.biddy.memberservice.domain.repository.MemberRepository;
import com.biddy.memberservice.domain.repository.RefreshTokenRepository;
import com.biddy.memberservice.infrastructure.security.JwtBlacklistService;
import com.biddy.memberservice.infrastructure.security.JwtTokenProvider;
import com.biddy.memberservice.infrastructure.security.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private EmailVerificationRepository emailVerificationRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtBlacklistService jwtBlacklistService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JavaMailSender mailSender;
    @Mock private MemberEventPublisher eventPublisher;
    @Mock private TokenHasher tokenHasher;

    @InjectMocks
    private AuthService authService;

    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.builder()
                .id(1L)
                .email("user@biddy.com")
                .password("encoded-password")
                .role(MemberRole.USER)
                .status(MemberStatus.ACTIVE)
                .build();
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        return request;
    }

    @Test
    void login_성공하면_새_familyId로_토큰을_발급하고_이전_세션을_삭제한다() {
        given(memberRepository.findByEmail("user@biddy.com")).willReturn(Optional.of(member));
        given(passwordEncoder.matches("raw-password", "encoded-password")).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(1L, MemberRole.USER)).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(1L)).willReturn("refresh-token");
        given(tokenHasher.hash("refresh-token")).willReturn("hashed-refresh-token");
        given(refreshTokenRepository.save(any(RefreshToken.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        TokenResponse response = authService.login(loginRequest("user@biddy.com", "raw-password"));

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        verify(refreshTokenRepository).deleteByMemberId(1L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo("hashed-refresh-token");
        assertThat(captor.getValue().isRevoked()).isFalse();
        assertThat(captor.getValue().getFamilyId()).isNotBlank();
    }

    @Test
    void reissue_정상_토큰이면_기존_토큰을_revoke하고_같은_familyId로_새_토큰을_발급한다() {
        RefreshToken existing = RefreshToken.issue(member, "old-hash", "family-1", LocalDateTime.now().plusDays(1));
        given(tokenHasher.hash("old-refresh-token")).willReturn("old-hash");
        given(refreshTokenRepository.findByTokenHash("old-hash")).willReturn(Optional.of(existing));
        given(jwtTokenProvider.generateAccessToken(1L, MemberRole.USER)).willReturn("new-access-token");
        given(jwtTokenProvider.generateRefreshToken(1L)).willReturn("new-refresh-token");
        given(tokenHasher.hash("new-refresh-token")).willReturn("new-hash");
        given(refreshTokenRepository.save(any(RefreshToken.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        TokenResponse response = authService.reissue("old-refresh-token");

        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
        assertThat(existing.isRevoked()).isTrue();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(captor.capture());
        RefreshToken savedNew = captor.getAllValues().get(1);
        assertThat(savedNew.getTokenHash()).isEqualTo("new-hash");
        assertThat(savedNew.getFamilyId()).isEqualTo("family-1");
        assertThat(savedNew.isRevoked()).isFalse();
    }

    @Test
    void reissue_이미_사용된_revoked_토큰이_다시_들어오면_family_전체를_무효화하고_예외를_던진다() {
        RefreshToken alreadyUsed = RefreshToken.issue(member, "stolen-hash", "family-2", LocalDateTime.now().plusDays(1));
        alreadyUsed.revoke();
        given(tokenHasher.hash("stolen-refresh-token")).willReturn("stolen-hash");
        given(refreshTokenRepository.findByTokenHash("stolen-hash")).willReturn(Optional.of(alreadyUsed));

        assertThatThrownBy(() -> authService.reissue("stolen-refresh-token"))
                .isInstanceOf(RefreshTokenReuseException.class);

        verify(refreshTokenRepository).revokeAllByFamilyId("family-2");
        verify(jwtTokenProvider, never()).generateAccessToken(any(), any());
    }

    @Test
    void reissue_존재하지_않는_토큰이면_IllegalArgumentException을_던진다() {
        given(tokenHasher.hash("unknown-token")).willReturn("unknown-hash");
        given(refreshTokenRepository.findByTokenHash("unknown-hash")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue("unknown-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("유효하지 않은 토큰입니다.");
    }

    @Test
    void reissue_만료된_토큰이면_IllegalArgumentException을_던진다() {
        RefreshToken expired = RefreshToken.issue(member, "expired-hash", "family-3", LocalDateTime.now().minusSeconds(1));
        given(tokenHasher.hash("expired-token")).willReturn("expired-hash");
        given(refreshTokenRepository.findByTokenHash("expired-hash")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.reissue("expired-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("만료된 토큰입니다.");
    }
}
