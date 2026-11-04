package com.agora.identity;
import com.agora.identity.application.TokenCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TokenCodecTests {
 @Test void randomOpaqueTokensAndAuthenticatedEncryptionBindActionAndPurpose(){
  var codec=new TokenCodec("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
  String token=codec.create();UUID id=UUID.randomUUID();byte[] sealed=codec.seal(id,"VERIFY_EMAIL",token);
  assertThat(codec.create()).isNotEqualTo(token);assertThat(codec.digest(token)).hasSize(32);
  assertThat(codec.open(id,"VERIFY_EMAIL",sealed)).isEqualTo(token);
  assertThat(new String(sealed,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(token);
  assertThatThrownBy(()->codec.open(UUID.randomUUID(),"VERIFY_EMAIL",sealed)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->codec.open(id,"RESET_PASSWORD",sealed)).isInstanceOf(IllegalStateException.class);
  sealed[20]^=1;assertThatThrownBy(()->codec.open(id,"VERIFY_EMAIL",sealed)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->new TokenCodec("bad")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->codec.digest("invalid")).isInstanceOf(IllegalArgumentException.class);
 }
}
