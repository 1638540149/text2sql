package com.text2sql.security;

import com.text2sql.mapper.CoreMapper;
import java.util.Map;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    private final CoreMapper mapper;

    public UserDetailsServiceImpl(CoreMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Map<String, Object> row = mapper.findUserByUsername(username);
        if (row == null) {
            throw new UsernameNotFoundException("User not found");
        }
        return new LoginUser(
            ((Number) row.get("id")).longValue(),
            String.valueOf(row.get("username")),
            String.valueOf(row.get("passwordHash")),
            String.valueOf(row.get("role")),
            String.valueOf(row.get("displayName"))
        );
    }
}
