package com.example.cinema.user.authentication;

import java.util.Optional;

public interface CurrentUser {

    Optional<AuthenticatedUserIdentity> optional();

    AuthenticatedUserIdentity require();
}
