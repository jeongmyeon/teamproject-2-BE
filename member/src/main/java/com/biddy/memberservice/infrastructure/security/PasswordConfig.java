package com.biddy.memberservice.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 암호화 설정.
 *
 * <p>기존 SecurityConfig(인가 규칙)를 제거하면서 PasswordEncoder 빈만 이 클래스로 분리했다.
 * AuthService/MemberService가 회원가입/비밀번호 변경 시 계속 사용한다.</p>
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
