package com.biddy.memberservice.application.service;

import com.biddy.memberservice.application.dto.request.LoginRequest;
import com.biddy.memberservice.application.dto.request.SignupRequest;
import com.biddy.memberservice.application.dto.response.TokenResponse;
import com.biddy.memberservice.application.event.MemberEventPublisher;
import com.biddy.memberservice.domain.enums.MemberStatus;
import com.biddy.memberservice.domain.exception.RefreshTokenReuseException;
import com.biddy.memberservice.domain.model.EmailVerification;
import com.biddy.memberservice.domain.model.Member;
import com.biddy.memberservice.domain.model.RefreshToken;
import com.biddy.memberservice.domain.repository.EmailVerificationRepository;
import com.biddy.memberservice.domain.repository.MemberRepository;
import com.biddy.memberservice.domain.repository.RefreshTokenRepository;
import com.biddy.memberservice.infrastructure.security.JwtBlacklistService;
import com.biddy.memberservice.infrastructure.security.JwtTokenProvider;
import com.biddy.memberservice.infrastructure.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtBlacklistService jwtBlacklistService;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final MemberEventPublisher eventPublisher;
    private final TokenHasher tokenHasher;

    @Transactional
    @SneakyThrows
    public void signup(SignupRequest request) {
        boolean verified = emailVerificationRepository
                .existsByEmailAndVerifiedAtIsNotNull(request.getEmail());
        if (!verified) {
            throw new IllegalArgumentException("이메일 인증이 필요합니다.");
        }
        memberRepository.findByEmail(request.getEmail()).ifPresent(existing -> {
            if (existing.getStatus() == MemberStatus.SUSPENDED) {
                throw new IllegalArgumentException("정지된 계정의 이메일로는 가입할 수 없습니다.");
            }
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        });
        if (memberRepository.existsByNickname(request.getNickname())) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
        }

        Member member = Member.create(
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                request.getNickname(),
                request.getPhone()
        );
        Member savedMember = memberRepository.save(member);

        eventPublisher.publishSignup(savedMember.getId());
        log.info("Kafka 이벤트 발행: topic=member-signup, memberId={}", savedMember.getId());
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new IllegalArgumentException("탈퇴 처리 중인 계정입니다.");
        }
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new IllegalArgumentException("정지된 계정입니다.");
        }
        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(member.getId(), member.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(member.getId());

        refreshTokenRepository.deleteByMemberId(member.getId());
        String familyId = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.issue(
                member,
                tokenHasher.hash(refreshToken),
                familyId,
                LocalDateTime.now().plusDays(7)
        ));

        return TokenResponse.of(accessToken, refreshToken);
    }

    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public TokenResponse reissue(String refreshToken) {
        String tokenHash = tokenHasher.hash(refreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 토큰입니다."));

        if (token.isRevoked()) {
            refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId());
            log.warn("refresh token 재사용 감지: memberId={}, familyId={}",
                    token.getMember().getId(), token.getFamilyId());
            throw new RefreshTokenReuseException(
                    "비정상적인 토큰 재사용이 감지되어 모든 세션이 종료되었습니다. 다시 로그인해 주세요.");
        }

        if (token.isExpired()) {
            throw new IllegalArgumentException("만료된 토큰입니다.");
        }

        Member member = token.getMember();
        token.revoke();
        refreshTokenRepository.save(token);

        String newAccessToken = jwtTokenProvider.generateAccessToken(member.getId(), member.getRole());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(member.getId());

        refreshTokenRepository.save(RefreshToken.issue(
                member,
                tokenHasher.hash(newRefreshToken),
                token.getFamilyId(),
                LocalDateTime.now().plusDays(7)
        ));

        return TokenResponse.of(newAccessToken, newRefreshToken);
    }

    @Transactional
    public void logout(Long memberId, String accessToken) {
        refreshTokenRepository.deleteByMemberId(memberId);
        jwtBlacklistService.blacklist(accessToken);
    }

    @Transactional
    public void sendVerificationEmail(String email) {
        memberRepository.findByEmail(email).ifPresent(existing -> {
            if (existing.getStatus() == MemberStatus.SUSPENDED) {
                throw new IllegalArgumentException("정지된 계정의 이메일로는 가입할 수 없습니다.");
            }
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        });
        String token = String.format("%06d", new java.util.Random().nextInt(1000000));
        emailVerificationRepository.save(EmailVerification.create(
                email,
                token,
                LocalDateTime.now().plusMinutes(10)
        ));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("Biddy <tlsdlcl456@gmail.com>");
        message.setTo(email);
        message.setSubject("[Biddy] 이메일 인증");
        message.setText("아래 인증 코드를 입력해주세요.\n\n인증 코드: " + token + "\n\n10분 후 만료됩니다.");
        mailSender.send(message);
    }

    @Transactional
    public void verifyEmail(String email, String token) {
        EmailVerification verification = emailVerificationRepository
                .findTopByEmailOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new IllegalArgumentException("인증 정보를 찾을 수 없습니다."));

        if (verification.isExpired()) {
            throw new IllegalArgumentException("만료된 인증 토큰입니다.");
        }
        if (!verification.getToken().equals(token)) {
            throw new IllegalArgumentException("유효하지 않은 인증 토큰입니다.");
        }

        verification.verify();
        emailVerificationRepository.save(verification);
    }
}
