package com.agora.identity.adapter.inbound;

import com.agora.identity.application.EmailActions;
import com.agora.identity.application.IdentityService;
import com.agora.identity.domain.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class EmailActionsController {
 private final EmailActions actions;private final IdentityService identity;
 public EmailActionsController(EmailActions actions,IdentityService identity){this.actions=actions;this.identity=identity;}
 @PostMapping("/auth/email-verifications") @ResponseStatus(HttpStatus.ACCEPTED)
 java.util.Map<String,String> resend(@Valid @RequestBody EmailRequest body){actions.request(body.email(),"VERIFY_EMAIL");return accepted();}
 @PostMapping("/auth/password-resets") @ResponseStatus(HttpStatus.ACCEPTED)
 java.util.Map<String,String> forgot(@Valid @RequestBody EmailRequest body){actions.request(body.email(),"RESET_PASSWORD");return accepted();}
 @PostMapping("/auth/email-verifications/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
 void verify(@Valid @RequestBody TokenRequest body){actions.confirm(body.token(),"VERIFY_EMAIL",null);}
 @PostMapping("/auth/password-resets/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
 void reset(@Valid @RequestBody ResetRequest body){actions.confirm(body.token(),"RESET_PASSWORD",body.newPassword());}
 @PostMapping("/auth/password-changes/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
 void confirmChange(@Valid @RequestBody TokenRequest body){actions.confirm(body.token(),"CHANGE_PASSWORD",null);}
 @PostMapping("/me/password-changes") @ResponseStatus(HttpStatus.ACCEPTED)
 java.util.Map<String,String> change(@Valid @RequestBody ChangeRequest body,Principal principal,HttpServletRequest request){
  var session=request.getSession();var account=identity.authenticated(UUID.fromString(principal.getName()),session.getAttribute("AGORA_ACCOUNT_TYPE"),session.getAttribute("AGORA_ACCOUNT_VERSION"));
  actions.change(account.id(),(Long)session.getAttribute("AGORA_ACCOUNT_VERSION"),body.currentPassword(),body.newPassword());
  return accepted();
 }
 @PostMapping("/me/email-changes") @ResponseStatus(HttpStatus.ACCEPTED)
 java.util.Map<String,String> changeEmail(@Valid @RequestBody EmailChangeRequest body,Principal principal,HttpServletRequest request){
  var session=request.getSession();var account=identity.authenticated(UUID.fromString(principal.getName()),session.getAttribute("AGORA_ACCOUNT_TYPE"),session.getAttribute("AGORA_ACCOUNT_VERSION"));
  actions.changeEmail(account.id(),(Long)session.getAttribute("AGORA_ACCOUNT_VERSION"),body.currentPassword(),body.newEmail());return accepted();
 }
 @PostMapping("/auth/email-changes/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
 void confirmEmailChange(@Valid @RequestBody TokenRequest body){actions.confirm(body.token(),"CHANGE_EMAIL",null);}
 record EmailChangeRequest(@NotBlank @Size(max=256)String currentPassword,@NotBlank @Email @Size(max=254)String newEmail){EmailChangeRequest{newEmail=Account.normalizeEmail(newEmail);}@Override public String toString(){return "EmailChangeRequest[redacted]";}}
 private static java.util.Map<String,String> accepted(){return java.util.Map.of("message","Nếu yêu cầu đủ điều kiện, link xác nhận sẽ được gửi qua email. Vui lòng chờ ít nhất một phút trước khi yêu cầu lại.");}
 record EmailRequest(@NotBlank @Email @Size(max=254)String email){EmailRequest{email=Account.normalizeEmail(email);}@Override public String toString(){return "EmailRequest[redacted]";}}
 record TokenRequest(@NotBlank @Size(min=43,max=43)String token){@Override public String toString(){return "TokenRequest[redacted]";}}
 record ResetRequest(@NotBlank @Size(min=43,max=43)String token,@NotNull @Size(min=12,max=256)String newPassword){@Override public String toString(){return "ResetRequest[redacted]";}}
 record ChangeRequest(@NotNull @Size(min=1,max=256)String currentPassword,@NotNull @Size(min=12,max=256)String newPassword){@Override public String toString(){return "ChangeRequest[redacted]";}}
}
