package io.agentscope.platform.auth.rbac;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 表单登录用的用户源。角色转成 ROLE_*，权限码原样作为 authority。 */
@Service
public class RbacUserDetailsService implements UserDetailsService {

    /** v1 单租户，租户码固定。多租户接入时改从请求上下文解析。 */
    public static final String DEFAULT_TENANT = "default";

    private final UserRepository repository;

    public RbacUserDetailsService(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = repository.findByUsername(DEFAULT_TENANT, username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));

        List<GrantedAuthority> authorities = new ArrayList<>();
        account.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        account.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

        return User.withUsername(account.username())
                .password(account.passwordHash())
                .authorities(authorities)
                .disabled(!account.active())
                .build();
    }
}
