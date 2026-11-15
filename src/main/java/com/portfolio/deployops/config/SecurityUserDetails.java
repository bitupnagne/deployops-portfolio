package com.portfolio.deployops.config;

import com.portfolio.deployops.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;

@Getter
public class SecurityUserDetails implements UserDetails {

    private final User user;
    private final boolean accountNonLocked;

    public SecurityUserDetails(User user) {
        this(user, true);
    }

    public SecurityUserDetails(User user, boolean accountNonLocked) {
        this.user = user;
        this.accountNonLocked = accountNonLocked;
    }

    public Long getId() { return user.getId(); }
    public String getName() { return user.getName(); }
    /**
     * 역할 <b>코드</b>. 권한 비교({@code role == 'SUPER_ADMIN'})에 쓰이므로 문자열로 둔다.
     * 화면에 찍을 때는 {@link #getRoleLabel()} 을 쓸 것 — 이걸 그대로 보여 주면
     * 사이드바에 SUPER_ADMIN 이 나온다.
     */
    public String getRole() { return user.getRole().name(); }

    /** 사람이 읽는 역할 이름. */
    public String getRoleLabel() {
        return user.getRole() == null ? "-" : user.getRole().getLabel();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override public String getPassword() { return user.getPassword(); }
    @Override public String getUsername() { return user.getLoginId(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return accountNonLocked; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isActive(); }
}
