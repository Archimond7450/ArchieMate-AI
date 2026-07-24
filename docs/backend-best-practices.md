# Backend Best Practices

Guidelines for writing clean, idiomatic backend code in the ArchieMate codebase.

---

## Pekko Typed Actor Patterns

### Object vs Class Pattern

**Small actors** — simple behavior with minimal setup — use a singleton `object` with a private `actorName` and a single `apply` method that delegates to a private behavior method.

**Larger actors** — those needing timers, stash, context wiring, or multi-phase initialization — use a companion `object` that instantiates a class, and a `class` that exposes only `initial` as its public API.

### Command Trait

The command trait **must always be sealed** and **must always be named `Command`**. All case classes inside it **must be `final case class`** (case classes are extendable in Scala 3, so `final` is required). Case objects are final by default in Scala 3, so `final` on `case object` is redundant and should be omitted.

```scala
sealed trait Command
final case class Register(actorRef: ActorRef[Ready]) extends Command
final case class CheckReadiness(replyTo: ActorRef[ReadinessResponse]) extends Command
case object Ready extends Command
```

### Per-Command Response Traits

Each command must have its own sealed response trait. Case classes can extend multiple traits — this avoids duplication while keeping the type system precise. Callers create `TestProbe[SpecificResponse]` to get exhaustive match checking on only the relevant responses.

```scala
object MyActor {
  sealed trait Command
  final case class Start(replyTo: ActorRef[StartResponse]) extends Command
  final case class Stop(replyTo: ActorRef[StopResponse]) extends Command

  sealed trait Response

  sealed trait StartResponse extends Response
  sealed trait StopResponse extends Response

  final case class StartSuccess(id: String) extends StartResponse
  final case class StopSuccess(flushed: Boolean) extends StopResponse
  final case class Error(message: String) extends StartResponse with StopResponse
}
```

This prevents callers matching on a `Start` response from accidentally seeing `StopSuccess` as a possible case. The compiler enforces exhaustiveness only over the responses that can actually be received for that command.

### actorName Convention

The companion `object` must contain a `private val actorName`:

```scala
private val actorName = "my-actor"
```

Use a `private def actorName` when the name must be computed dynamically (e.g., based on configuration).

### The apply Method

The companion `object`'s `apply` method is the **only public entry point**. It must:

1. For small actors: directly call a private behavior method.
2. For larger actors: instantiate the companion class, call `initial`, and return the result.

### The initial Method

For larger actors, the companion `class` exposes a single public method `initial`. This method:

1. Performs one-time setup (timers, stash, context wiring).
2. Returns the main behavior.
3. May be called multiple times in a chain if the actor needs to await responses from other actors.

### Context Extraction

For larger actors, the actor context must be extracted in the companion object's `apply` method and passed to the class constructor. Use `Behaviors.receiveMessage` instead of `Behaviors.receive`.

### State Management

**Never use `var` or `while`/`for` loops** in actors or their companions. Pekko Typed is inherently functional — state lives in method parameters.

```scala
private case class TrackerState(
  registry: Set[ActorRef[Ready]] = Set.empty,
  ready: Set[ActorRef[Ready]] = Set.empty
)

mainBehavior(state.copy(ready = state.ready + sender))
```

### Supervising Root Behaviors

When the root behavior handles `Nothing`, specify the type parameter on `supervise`:

```scala
Behaviors.supervise[Nothing] {
  Behaviors.setup { ctx => /* ... */ }
}.onFailure[Throwable](SupervisorStrategy.restart)
```

### ActorRef Invariance

`ActorRef` is **invariant** in its type parameter. When passing a ref to a registry that stores but never invokes it, use `ActorRef[Nothing]`:

```scala
tracker ! ReadinessTracker.Register(ctx.self.asInstanceOf[ActorRef[Nothing]])
```

### pipeToSelf vs onComplete

`pipeToSelf`'s callback has type `Try[T] => Unit` — it **cannot return a `Behavior`**. When you need to return a `Behavior` from the callback, use `onComplete` on the `Future` directly.

### Logging

**Always use the Actor Context's `log` method — never `LoggerFactory`.** The context logger is wired to Pekko's structured logging infrastructure, includes the actor's path in every log line, and respects the actor's log level configuration.

### No Await

**Never use `Await.result`, `Await.ready`, or `Await` in Pekko Typed code.** Blocking a thread defeats the purpose of the actor model. Use futures and callbacks, or `ask` patterns with `onComplete` / `map` / `flatMap`.

---

## Actor Testing

Actor tests must extend **`ScalaTestWithActorTestKit`** and **`AnyWordSpecLike`** (preferred over `AnyWordSpec` for consistency across the project).

```scala
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MyActorSpec extends AnyWordSpecLike with ScalaTestWithActorTestKit with Matchers {
  private val probe = testKit.createTestProbe[Command]()

  "MyActor" should {
    "do something" in {
      val actor = testKit.createTestProbe(MyActor())
      actor.expectMessageType[Response]
    }
  }
}
```

Use `testKit.createTestProbe[T]()` to create test probes. Use `!` to send messages and `expectMessageType[T]`, `expectMessage`, `receiveMessage()`, etc. to verify responses.

---

## ClassicActorSystemProvider

When `ClassicActorSystemProvider` is required, retrieve it from the typed Actor System's `classicSystem` property. **Do not create a new `ClassicActorSystemProvider` manually** unless you are in a test or the root `main` method where no typed system exists yet.

**Do this:**
```scala
given ClassicActorSystemProvider = ctx.system.classicSystemProvider
```

**Not this:**
```scala
val provider = new ClassicActorSystemProvider {
  def classicSystem = org.apache.pekko.actor.ActorSystem("foo", config)
}
```

---

## HTTP Endpoints

### Prefer helper actors over nested asks

When an HTTP endpoint needs to perform multiple actor asks, **prefer asking a helper actor once** instead of performing multiple nested asks. This reduces latency (parallel execution), simplifies error handling, and avoids callback nesting.

**Do this:**
```scala
// Single ask to a helper actor that does both lookups internally
userActor.ask[UserData](ref => UserDataRequest(userId, ref))
```

**Not this:**
```scala
userActor.ask[UserInfo](ref => UserInfoRequest(userId, ref)).flatMap { info =>
  profileActor.ask[ProfileData](ref => ProfileRequest(info.profileId, ref)).map { profile =>
    complete(info -> profile)
  }
}
```

### Prefer case classes over string literals for HTTP responses

When returning JSON data from HTTP endpoints, **always use case classes** that get implicitly encoded by circe.

---

## Logging (non-actor code)

Use `classOf[ObjectName.type]` for singleton objects, not `classOf[ObjectName]`:

```scala
private val logger = LoggerFactory.getLogger(classOf[MyApp.type])
```
