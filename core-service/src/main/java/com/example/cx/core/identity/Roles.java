package com.example.cx.core.identity;

import com.example.cx.core.security.Role;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Roles are stored as ",ADMIN,AGENT," (leading/trailing comma so LIKE '%,AGENT,%' is exact). Avoids a join (N+1) per user load. */
public final class Roles {
    private Roles() {}

    public static String format(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) return ",";
        return roles.stream().map(Enum::name).sorted().collect(Collectors.joining(",", ",", ","));
    }

    public static Set<Role> parse(String db) {
        Set<Role> out = EnumSet.noneOf(Role.class);
        if (db != null) Arrays.stream(db.split(",")).filter(s -> !s.isBlank()).map(Role::valueOf).forEach(out::add);
        return out;
    }

    public static String likePattern(Role r) { return "%," + r.name() + ",%"; }
}
