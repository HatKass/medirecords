package com.medirecords.auth;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * Spring Security user that also carries the profile fields we put into the JWT,
 * so issuing a token after login needs no second database lookup.
 */
public class AuthenticatedUser extends User {

    private final long userId;
    private final String fullName;
    private final Role role;

    AuthenticatedUser(AppUser user) {
        super(user.getUsername(), user.getPasswordHash(), user.isEnabled(), true, true, true,
                List.of(new SimpleGrantedAuthority(user.getRole().authority())));
        this.userId = user.getId();
        this.fullName = user.getFullName();
        this.role = user.getRole();
    }

    public long getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }
}
