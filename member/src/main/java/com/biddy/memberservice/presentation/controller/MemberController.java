package com.biddy.memberservice.presentation.controller;

import com.biddy.memberservice.application.dto.request.UpdateNicknameRequest;
import com.biddy.memberservice.application.dto.request.UpdatePasswordRequest;
import com.biddy.memberservice.application.dto.response.MemberResponse;
import com.biddy.memberservice.application.dto.response.NicknameResponse;
import com.biddy.memberservice.application.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/me")
    public ResponseEntity<MemberResponse> getMyInfo(@RequestHeader("X-Member-Id") Long memberId) {
        return ResponseEntity.ok(memberService.getMyInfo(memberId));
    }

    @PatchMapping("/me/nickname")
    public ResponseEntity<Void> updateNickname(@RequestHeader("X-Member-Id") Long memberId,
                                               @Valid @RequestBody UpdateNicknameRequest request) {
        memberService.updateNickname(memberId, request.getNickname());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/me/password")
    public ResponseEntity<Void> updatePassword(@RequestHeader("X-Member-Id") Long memberId,
                                               @Valid @RequestBody UpdatePasswordRequest request) {
        memberService.updatePassword(memberId, request.getCurrentPassword(),
                request.getNewPassword());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@RequestHeader("X-Member-Id") Long memberId) {
        memberService.withdraw(memberId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/nickname")
    public ResponseEntity<NicknameResponse> getMemberNickname(@PathVariable Long id) {
        String nickname = memberService.getNickname(id);
        return ResponseEntity.ok(NicknameResponse.of(nickname));
    }
}
