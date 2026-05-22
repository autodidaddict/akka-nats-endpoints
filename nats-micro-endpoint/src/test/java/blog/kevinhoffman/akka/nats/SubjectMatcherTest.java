package blog.kevinhoffman.akka.nats;

import static org.assertj.core.api.Assertions.assertThat;

import blog.kevinhoffman.akka.nats.internal.SubjectMatcher;
import org.junit.jupiter.api.Test;

class SubjectMatcherTest {

  @Test
  void exactSubjectExtractsNoTokens() {
    SubjectMatcher.Match match = SubjectMatcher.match("a.b.c", "a.b.c");
    assertThat(match.tokens()).isEmpty();
    assertThat(match.namedTokens()).isEmpty();
  }

  @Test
  void singleTokenWildcardExtractsAPositionalToken() {
    SubjectMatcher.Match match = SubjectMatcher.match("orders.*.created", "orders.42.created");
    assertThat(match.tokens()).containsExactly("42");
    assertThat(match.namedTokens()).isEmpty();
  }

  @Test
  void namedTokenIsExtractedByName() {
    SubjectMatcher.Match match = SubjectMatcher.match("echo.repeat.{count}", "echo.repeat.5");
    assertThat(match.tokens()).containsExactly("5");
    assertThat(match.namedTokens()).containsEntry("count", "5");
  }

  @Test
  void multipleNamedTokensAreExtractedInOrder() {
    SubjectMatcher.Match match =
        SubjectMatcher.match("orders.{id}.{action}", "orders.42.create");
    assertThat(match.tokens()).containsExactly("42", "create");
    assertThat(match.namedTokens())
        .containsEntry("id", "42")
        .containsEntry("action", "create");
  }

  @Test
  void tailWildcardCapturesTheRemainingTokens() {
    SubjectMatcher.Match match = SubjectMatcher.match("echo.subject.>", "echo.subject.orders.created");
    assertThat(match.tokens()).containsExactly("orders.created");
  }

  @Test
  void toNatsSubjectReplacesNamedTokensWithStarWildcards() {
    assertThat(SubjectMatcher.toNatsSubject("echo.repeat.{count}")).isEqualTo("echo.repeat.*");
    assertThat(SubjectMatcher.toNatsSubject("orders.{id}.{action}")).isEqualTo("orders.*.*");
  }

  @Test
  void toNatsSubjectLeavesPlainAndWildcardSubjectsUnchanged() {
    assertThat(SubjectMatcher.toNatsSubject("a.b.c")).isEqualTo("a.b.c");
    assertThat(SubjectMatcher.toNatsSubject("a.*.>")).isEqualTo("a.*.>");
  }
}
