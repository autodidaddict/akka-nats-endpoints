package blog.kevinhoffman.akka.nats.synadia.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link SynadiaSubjects} — subject derivation and identity-token validation. */
class SynadiaSubjectsTest {

  @Test
  void derivesThePromptSubjectFromTheIdentityTriple() {
    assertThat(SynadiaSubjects.prompt("echo", "acme", "echo-1"))
        .isEqualTo("agents.prompt.echo.acme.echo-1");
  }

  @Test
  void derivesTheStatusSubjectFromTheIdentityTriple() {
    assertThat(SynadiaSubjects.status("echo", "acme", "echo-1"))
        .isEqualTo("agents.status.echo.acme.echo-1");
  }

  @Test
  void derivesTheProtocolFixedHeartbeatSubjectFromTheIdentityTriple() {
    assertThat(SynadiaSubjects.heartbeat("echo", "acme", "echo-1"))
        .isEqualTo("agents.hb.echo.acme.echo-1");
  }

  @Test
  void acceptsASubjectTokenSafeIdentityElement() {
    SynadiaSubjects.validateToken("agent", "echo-1");
  }

  @Test
  void rejectsABlankIdentityElement() {
    assertThatThrownBy(() -> SynadiaSubjects.validateToken("agent", "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank");
  }

  @Test
  void rejectsAnIdentityElementContainingASubjectSeparator() {
    assertThatThrownBy(() -> SynadiaSubjects.validateToken("agent", "echo.1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnIdentityElementContainingAWildcard() {
    assertThatThrownBy(() -> SynadiaSubjects.validateToken("agent", "echo*"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SynadiaSubjects.validateToken("agent", "echo>"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnIdentityElementContainingWhitespace() {
    assertThatThrownBy(() -> SynadiaSubjects.validateToken("agent", "echo 1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
