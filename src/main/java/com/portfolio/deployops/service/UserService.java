package com.portfolio.deployops.service;

import com.portfolio.deployops.config.PasswordPolicy;
import com.portfolio.deployops.domain.Team;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.TeamRepository;
import com.portfolio.deployops.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TeamRepository teamRepository;
    private final SecretCryptoService secretCryptoService;
    private final AuditLogService auditLogService;

    /** 인증이 완료된 사용자만 기록하고, 커밋 이후 같은 principal에 표시 시각을 반영한다. */
    @Transactional
    public void recordLogin(User user) {
        recordLogin(user, null);
    }

    @Transactional
    public void recordLogin(User user, String ip) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("로그인 기록 트랜잭션을 확인할 수 없습니다.");
        }
        var at = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (userRepository.updateLastLogin(user.getId(), at) != 1) {
            throw new IllegalStateException("로그인 시각을 기록할 수 없습니다.");
        }
        auditLogService.recordAuthentication(user, null, "로그인", com.portfolio.deployops.domain.AuditResult.SUCCESS,
            "AUTHENTICATION_COMPLETED", ip);
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() { user.setLastLoginAt(at); }
            });
    }

    public void recordLoginFailure(String loginId, String ip) {
        // 검증 실패는 인증 성공이나 세션 생성으로 바꾸지 않는다.
        try {
            User user = loginId == null ? null : userRepository.findByLoginId(loginId).orElse(null);
            auditLogService.recordAuthentication(user, loginId, "로그인", com.portfolio.deployops.domain.AuditResult.FAIL,
                "PASSWORD_AUTHENTICATION_FAILED", ip);
        } catch (RuntimeException ignored) {
            // 인증 실패 응답을 유지한다. 저장 실패는 감사 서비스의 운영 장애로 별도 관측한다.
            org.slf4j.LoggerFactory.getLogger(UserService.class).error("인증 실패 감사 기록을 저장하지 못했습니다.");
        }
    }

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAllWithTeam();
    }

    @Transactional(readOnly = true)
    public List<User> findApprovers() {
        return userRepository.findByRoleAndActiveTrue(UserRole.APPROVER);
    }

    /**
     * 단건 조회. 감사로그가 <b>변경 전</b> 상태를 찍기 위해 쓴다 —
     * update 를 부르고 나면 역할이 무엇이었는지 알 방법이 없다.
     */
    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    @Transactional
    public User changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (!user.isActive() || currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BadCredentialsException("현재 비밀번호가 올바르지 않습니다.");
        }
        PasswordPolicy.validate(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호와 다른 새 비밀번호를 입력해 주세요.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setForcePasswordChange(false);
        user.setPasswordChangedAt(java.time.LocalDateTime.now());
        persistPassword(user);
        auditLogService.recordAuthentication(user, null, "본인 비밀번호 변경", com.portfolio.deployops.domain.AuditResult.SUCCESS,
            "PASSWORD_CHANGED", null);
        return user;
    }

    /** 거부된 변경의 감사 기록은 비밀번호 변경 롤백과 분리하며, 저장 실패는 전파한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPasswordChangeFailure(User authenticatedUser, String category) {
        if (authenticatedUser == null || authenticatedUser.getId() == null
                || !("WRONG_CURRENT_PASSWORD".equals(category) || "PASSWORD_POLICY_REJECTED".equals(category))) {
            throw new IllegalArgumentException("비밀번호 변경 실패 감사 정보가 올바르지 않습니다.");
        }
        auditLogService.recordAuthentication(authenticatedUser, null, "본인 비밀번호 변경",
            com.portfolio.deployops.domain.AuditResult.FAIL, category, null);
    }

    /** 관리자 발급 임시 비밀번호. 기존 비밀번호를 재사용하지 않고 다음 로그인에서 변경하도록 한다. */
    @Transactional
    public User resetPassword(Long userId, String temporaryPassword) {
        PasswordPolicy.validate(temporaryPassword);
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        boolean passwordWasReused;
        try {
            passwordWasReused = passwordEncoder.matches(temporaryPassword, user.getPassword());
        } catch (IllegalArgumentException invalidStoredHash) {
            // A legacy or damaged hash must not prevent an administrator from recovering the account.
            passwordWasReused = false;
        }
        if (passwordWasReused) {
            throw new IllegalArgumentException("기존 비밀번호와 다른 임시 비밀번호를 입력해 주세요.");
        }
        user.setPassword(passwordEncoder.encode(temporaryPassword));
        user.setForcePasswordChange(true);
        user.setPasswordChangedAt(null);
        persistPassword(user);
        return user;
    }

    private void persistPassword(User user) {
        if (userRepository.updatePassword(user) != 1) {
            throw new IllegalStateException("비밀번호 변경 결과를 확인할 수 없습니다.");
        }
    }

    @Transactional
    public User create(com.portfolio.deployops.dto.UserForm form) {
        if (form.getPassword() == null || form.getPassword().isBlank()) {
            throw new IllegalArgumentException("초기 비밀번호를 입력해 주세요.");
        }
        PasswordPolicy.validate(form.getPassword());
        if (userRepository.existsByLoginId(form.getLoginId())) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        }
        User user = User.builder()
            .loginId(form.getLoginId())
            .password(passwordEncoder.encode(form.getPassword()))
            .name(form.getName())
            .role(form.getRole())
            .active(form.isActive())
            // W-05: 관리자가 만들어 준 초기 비밀번호는 첫 로그인에 반드시 변경.
            .forcePasswordChange(true)
            .build();
        if (form.getGitlabToken() != null && !form.getGitlabToken().isBlank()) {
            user.setGitlabToken(secretCryptoService.encryptIfPlain(form.getGitlabToken().trim()));
        }
        user.setGitlabUsername(normalize(form.getGitlabUsername()));
        applyTeam(user, form.getTeamId());
        return userRepository.save(user);
    }

    @Transactional
    public User update(Long id, com.portfolio.deployops.dto.UserForm form) {
        User user = userRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setName(form.getName());
        user.setRole(form.getRole());
        user.setActive(form.isActive());
        // gitlabToken: null이면 유지, 빈 문자열이면 해제, 값이 있으면 암호화해 갱신한다.
        if (form.getGitlabToken() != null) {
            if (form.getGitlabToken().isBlank()) {
                user.setGitlabToken(null);
            } else {
                user.setGitlabToken(secretCryptoService.encryptIfPlain(form.getGitlabToken().trim()));
            }
        }
        user.setGitlabUsername(normalize(form.getGitlabUsername()));
        applyTeam(user, form.getTeamId());
        return userRepository.save(user);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void applyTeam(User user, Long teamId) {
        if (teamId == null) {
            user.setTeam(null);
        } else {
            Team team = teamRepository.findById(teamId).orElse(null);
            user.setTeam(team);
        }
    }

    @Transactional
    public void deactivate(Long id) {
        User user = userRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setActive(false);
        userRepository.save(user);
    }

    /** 매핑 운영 UI 전용 — gitlab_username 만 설정/해제(토큰은 유지). */
    @Transactional
    public User assignGitlabUsername(Long userId, String gitlabUsername) {
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setGitlabUsername(normalize(gitlabUsername));
        return userRepository.save(user);
    }

    @Transactional
    public User saveGitlabToken(Long userId, String token, String username) {
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setGitlabToken(token == null || token.isBlank() ? null : secretCryptoService.encryptIfPlain(token.trim()));
        user.setGitlabUsername(username == null || username.isBlank() ? null : username.trim());
        return userRepository.save(user);
    }
}
