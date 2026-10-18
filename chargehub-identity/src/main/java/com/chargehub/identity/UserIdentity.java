package com.chargehub.identity;

import java.util.Set;
public record UserIdentity(long id, String username, Set<String> roles) {}
