package com.accelhack.monica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * What ingest answered for one envelope.
 *
 * <p>{@link MonicaTransport#send(MonicaEnvelope)} answers a bare {@code boolean}, which cannot
 * carry the reason for a rejection. {@code error.json} exists exactly for that reason: a
 * {@code 422} names the fields that made the envelope invalid, and without them a
 * misconfigured {@code beforeSend} drops every event in silence. This is the value
 * {@link MonicaTransport#deliver(MonicaEnvelope)} returns so the caller can see it.
 */
public final class SendResult {
  private static final SendResult ACCEPTED = new SendResult(true, 0, null, null, null, false);
  private static final SendResult FAILED = new SendResult(false, 0, null, null, null, false);

  private final boolean accepted;
  private final int status;
  private final String errorCode;
  private final String errorMessage;
  private final List<Issue> issues;
  private final boolean stopped;

  private SendResult(boolean accepted, int status, String errorCode, String errorMessage,
      List<Issue> issues, boolean stopped) {
    this.accepted = accepted;
    this.status = status;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.issues = issues == null || issues.isEmpty()
        ? Collections.emptyList()
        : Collections.unmodifiableList(new ArrayList<>(issues));
    this.stopped = stopped;
  }

  /**
   * A result with no HTTP status: a network failure, or a legacy {@code send} return value.
   *
   * <p>Nothing cross-checks {@code accepted} against the status on these factories: a transport
   * is the only thing that knows whether it counts a response as delivery, so keeping them
   * consistent (2xx and accepted go together) is the caller's job.
   */
  public static SendResult of(boolean accepted) {
    return accepted ? ACCEPTED : FAILED;
  }

  /** A result carrying the status ingest answered and nothing read out of the body. */
  public static SendResult of(boolean accepted, int status) {
    return new SendResult(accepted, status, null, null, null, false);
  }

  /** A rejection with whatever {@code error.json} the response body carried. */
  public static SendResult rejected(int status, String errorCode, String errorMessage,
      List<Issue> issues) {
    return rejected(status, errorCode, errorMessage, issues, false);
  }

  /**
   * A rejection that also closes the transport, as {@code transport.json} asks for {@code 401}
   * ({@code drop_and_stop}).
   */
  public static SendResult rejected(int status, String errorCode, String errorMessage,
      List<Issue> issues, boolean stopped) {
    return new SendResult(status >= 200 && status < 300, status, errorCode, errorMessage, issues,
        stopped);
  }

  /** True when ingest accepted the envelope (HTTP 2xx). */
  public boolean isAccepted() {
    return accepted;
  }

  /** The HTTP status, or empty when no response arrived (network failure, timeout). */
  public OptionalInt getStatus() {
    return status > 0 ? OptionalInt.of(status) : OptionalInt.empty();
  }

  /** {@code error.code} from the response body: for humans, never for branching. Nullable. */
  public String getErrorCode() {
    return errorCode;
  }

  /** {@code error.message} from the response body. Nullable. */
  public String getErrorMessage() {
    return errorMessage;
  }

  /** The field-level problems a {@code 422} reported. Empty for every other status. */
  public List<Issue> getIssues() {
    return issues;
  }

  /** True once the transport has stopped sending, which a {@code 401} does for good. */
  public boolean isStopped() {
    return stopped;
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder("SendResult{accepted=").append(accepted);
    if (status > 0) text.append(", status=").append(status);
    if (errorCode != null) text.append(", code=").append(errorCode);
    if (!issues.isEmpty()) text.append(", issues=").append(issues);
    if (stopped) text.append(", stopped");
    return text.append('}').toString();
  }

  /** One entry of {@code error.issues}: the JSON path that was wrong, and why. */
  public static final class Issue {
    private final String path;
    private final String message;

    public Issue(String path, String message) {
      this.path = path;
      this.message = message;
    }

    /** A JSON path into the envelope, such as {@code $.items[0].request.method}. */
    public String getPath() {
      return path;
    }

    public String getMessage() {
      return message;
    }

    @Override
    public String toString() {
      return path + ": " + message;
    }
  }
}
