package com.agora.identity.adapter.inbound;
import com.agora.identity.application.*;
import com.agora.identity.domain.AdminAccountFailure;
import jakarta.servlet.http.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {
 private final ProfileService profiles;private final IdentityService identity;
 public ProfileController(ProfileService profiles,IdentityService identity){this.profiles=profiles;this.identity=identity;}
 private ProfileService.Actor actor(Principal p,HttpServletRequest request){var session=request.getSession();var a=identity.authenticated(UUID.fromString(p.getName()),session.getAttribute("AGORA_ACCOUNT_TYPE"),session.getAttribute("AGORA_ACCOUNT_VERSION"));return new ProfileService.Actor(a.id(),(Long)session.getAttribute("AGORA_ACCOUNT_VERSION"));}
 @GetMapping("/profile") public ProfileStore.Profile read(Principal p,HttpServletRequest r){return profiles.read(actor(p,r));}
 @PatchMapping("/profile") public ProfileStore.Profile name(@RequestBody JsonNode body,Principal p,HttpServletRequest r){fields(body,Set.of("displayName","expectedVersion"));if(!body.path("displayName").isString())throw invalid();return profiles.name(actor(p,r),version(body),body.path("displayName").asString());}
 @DeleteMapping("/avatar") public ProfileStore.Profile remove(@RequestBody JsonNode body,Principal p,HttpServletRequest r){fields(body,Set.of("expectedVersion"));return profiles.remove(actor(p,r),version(body));}
 @PutMapping(value="/avatar",consumes="multipart/form-data") public ProfileStore.Profile upload(Principal p,HttpServletRequest r)throws Exception {
  var actor=actor(p,r);var parts=r.getParts();if(parts.size()!=3||!parts.stream().map(Part::getName).collect(java.util.stream.Collectors.toSet()).equals(Set.of("file","expectedVersion","uploadId")))throw invalid();
  var file=r.getPart("file");for(String name:List.of("expectedVersion","uploadId"))if(r.getPart(name).getSize()>64||r.getPart(name).getSubmittedFileName()!=null)throw invalid();
  String expected=new String(r.getPart("expectedVersion").getInputStream().readNBytes(65),java.nio.charset.StandardCharsets.UTF_8),key=new String(r.getPart("uploadId").getInputStream().readNBytes(65),java.nio.charset.StandardCharsets.UTF_8);
  if(!expected.matches("0|[1-9][0-9]{0,15}"))throw invalid();long version=Long.parseLong(expected);if(version>9007199254740990L)throw invalid();
  UUID id;try{id=UUID.fromString(key);if(!id.toString().equals(key))throw invalid();}catch(IllegalArgumentException e){throw invalid();}
  try{return profiles.avatar(actor,version,id,com.agora.media.ImageUpload.read(file));}catch(com.agora.media.MediaFailure failure){throw new AdminAccountFailure(failure.status(),failure.code(),failure.getMessage());}
 }
 static void fields(JsonNode body,Set<String> names){if(!body.isObject()||body.size()!=names.size()||!names.containsAll(body.propertyNames()))throw invalid();}
 static long version(JsonNode body){var v=body.path("expectedVersion");if(!v.isIntegralNumber()||!v.canConvertToLong()||v.asLong()<0||v.asLong()>9007199254740990L)throw invalid();return v.asLong();}
 static AdminAccountFailure invalid(){return new AdminAccountFailure(422,"VALIDATION_ERROR","Dữ liệu hồ sơ không hợp lệ.");}
}
