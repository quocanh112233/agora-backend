package com.agora.commerce.application;

import java.util.UUID;

public interface IdentityAccess {
    UUID resolve(String session,String csrf,boolean requireCsrf);
}
