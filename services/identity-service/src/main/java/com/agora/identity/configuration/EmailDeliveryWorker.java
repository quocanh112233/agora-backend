package com.agora.identity.configuration;
import com.agora.identity.application.EmailActions;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class EmailDeliveryWorker {
 private final EmailActions actions;
 @org.springframework.beans.factory.annotation.Value("${agora.email.delivery-enabled}") private boolean enabled;
 public EmailDeliveryWorker(EmailActions actions){this.actions=actions;}
 @Scheduled(fixedDelay=2000) public void deliver(){if(!enabled)return;try{actions.dispatch();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Email outbox deferred type={}",e.getClass().getSimpleName());}}
 @Scheduled(initialDelay=3600000,fixedDelay=3600000) public void cleanup(){actions.cleanup();}
}
