package com.agora.feedback.application;
import com.agora.feedback.domain.Feedback.*;
import java.util.UUID;
public interface FeedbackAccess {
    Actor resolve(String session,String csrf,boolean unsafe);
    Line eligible(UUID buyer,UUID order,UUID sku);
    UUID shop(UUID actor);
}
