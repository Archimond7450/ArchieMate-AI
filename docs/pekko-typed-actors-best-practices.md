# Apache Pekko Typed Actor Best Practices

Guidelines for writing clean, idiomatic Pekko Typed actors in Scala.

## Object vs Class Pattern

**Small actors** — simple behavior with minimal setup — use a singleton `object` with a private `actorName` and a single `apply` method that delegates to a private behavior method.

**Larger actors** — those needing timers, stash, context wiring, or multi-phase initialization — use a companion `object` that instantiates a class, and a `class` that exposes only `initial` as its public API.

## Command Trait
The command trait **must always be sealed** and **must always be named `Command`**. All case classes and case objects inside it **must be final** so that the compiler can warn about unexhaustive pattern matches.

```scala
sealed trait Command
final case class Register(actorRef: ActorRef[Ready]) extends Command
final case class CheckReadiness(replyTo: ActorRef[ReadinessResponse]) extends Command
final case object Ready extends Command
```

## Per-Command Response Traits
Each command must have its own sealed response trait. Case classes can extend multiple traits — this avoids duplication while keeping the type system precise. Callers create `TestProbe[SpecificResponse]` to get exhaustive match checking on only the relevant responses.

```scala
object MyActor {
  sealed trait Command
  final case class Start(replyTo: ActorRef[StartResponse]) extends Command
  final case class Stop(replyTo: ActorRef[StopResponse]) extends Command

  // Common supertype
  sealed trait Response

  // One trait per command
  sealed trait StartResponse extends Response
  sealed trait StopResponse extends Response

  // Case classes extend their own trait; shared types extend all
  final case class StartSuccess(id: String) extends StartResponse
  final case class StopSuccess(flushed: Boolean) extends StopResponse
  final case class Error(message: String) extends StartResponse with StopResponse
}
```

This prevents callers matching on a `Start` response from accidentally seeing `StopSuccess` as a possible case. The compiler enforces exhaustiveness only over the responses that can actually be received for that command.

## actorName Convention

The companion `object` must contain a `private val actorName`:

```scala
private val actorName = "my-actor"
```

Use a `private def actorName` when the name must be computed dynamically (e.g., based on configuration). This name is used when spawning the actor so it appears in logs and the actor hierarchy.

## The apply Method

The companion `object`'s `apply` method is the **only public entry point**. It must:

1. For small actors: directly call a private behavior method.
2. For larger actors: instantiate the companion class, call `initial`, and return the result.

```scala
// Small actor
def apply(): Behavior[MyCommand] =
  mainBehavior(defaultState)

// Larger actor
def apply(): Behavior[MyCommand] = {
  val instance = new MyActor()
  instance.initial()
}
```

## The initial Method

For larger actors, the companion `class` exposes a single public method `initial`. This method:

1. Performs one-time setup (timers, stash, context wiring).
2. Returns the main behavior (which may itself be returned by a further private setup step).
3. May be called multiple times in a chain if the actor needs to await responses from other actors before becoming fully operational.

```scala
class MyActor {
  def initial(): Behavior[MyCommand] = {
    // One-time setup
    val state = TrackerState()
    mainBehavior(state)
  }

  private def mainBehavior(state: TrackerState): Behavior[MyCommand] =
    Behaviors.receive { ... }
}
```

## Context Extraction
For larger actors, the actor context must be extracted in the companion object's `apply` method and passed to the class constructor. This avoids repeated `ctx` lookups and keeps the behavior clean.

```scala
object MyActor {
  def apply(): Behavior[Command] = {
    val ctx = Behaviors.setup { context =>
      // one-time setup using context
      context
    }
    new MyActor(ctx).initial()
  }
}

class MyActor private (ctx: ActorContext[Command]) {
  def initial(): Behavior[Command] =
    Behaviors.receiveMessage {
      case cmd =>
        cmd match {
          case Register(ref) => // ...
          case Check =>       // ...
        }
    }
}
```

Use `Behaviors.receiveMessage` instead of `Behaviors.receive` — it is simpler and avoids the `(ctx, msg)` tuple pattern. Match on the command directly inside the method body.

## State Management

**Never use `var` or `while`/`for` loops** in actors or their companions. Pekko Typed is inherently functional — state lives in method parameters.

- Track state in the behavior method's parameter.
- Return a new behavior with the updated state.
- When state grows beyond a few fields, extract it into a `case class`. Its `.copy` method is ideal for updating individual fields immutably.

```scala
private case class TrackerState(
  registry: Set[ActorRef[Ready]] = Set.empty,
  ready: Set[ActorRef[Ready]] = Set.empty
)

// Update one field
mainBehavior(state.copy(ready = state.ready + sender))
```

## Supervising Root Behaviors

When the root behavior handles `Nothing`, specify the type parameter on `supervise` to avoid needing `asInstanceOf`:

```scala
// Correct — no cast needed
Behaviors.supervise[Nothing] {
  Behaviors.setup { ctx =>
    // ...
  }
}.onFailure[Throwable](SupervisorStrategy.restart)
```

## ActorRef Invariance

`ActorRef` is **invariant** in its type parameter: `ActorRef[SubType]` is **not** a subtype of `ActorRef[SuperType]`. When passing a ref to a registry that stores but never invokes it, use `ActorRef[Nothing]` — the bottom type communicates "this ref is never sent messages":

```scala
tracker ! ReadinessTracker.Register(ctx.self.asInstanceOf[ActorRef[Nothing]])
```

`ActorRef[Nothing]` is preferred over `ActorRef[Any]` for registries that only compare refs for equality (e.g., a readiness tracker). Only use `ActorRef[Any]` when the registry genuinely needs to send messages of unknown type to the stored refs.

## pipeToSelf vs onComplete

`pipeToSelf`'s callback has type `Try[T] => Unit` — it **cannot return a `Behavior`**. When you need to return a `Behavior` from the callback, use `onComplete` on the `Future` directly:

```scala
// WRONG — callback cannot return Behavior
ctx.pipeToSelf(future) {
  case Success(_) => ctx.self ! SomeMessage
  case Failure(_) => Behaviors.stopped  // ❌ wrong return type
}

// CORRECT — use onComplete on the Future
future.onComplete {
  case Success(_) => ctx.self ! SomeMessage
  case Failure(_) => // handle error
}
Behaviors.same  // return the behavior from the case
```

Actors that must preserve state across failures should be supervised with a **resume** strategy. When there is only one `SupervisorStrategy` for the entire error hierarchy, explicitly use the most generic type — `Throwable` — rather than a narrower type like `Exception`:

```scala
Behaviors.supervise(actor()).onFailure[Throwable](SupervisorStrategy.resume)
```

This ensures the actor is restarted without losing its internal state, since state is carried in behavior parameters rather than mutable fields.

## Logging

**Always use the Actor Context's `log` method — never `LoggerFactory`.** The context logger is wired to Pekko's structured logging infrastructure, includes the actor's path in every log line, and respects the actor's log level configuration.

Extract the context in `Behaviors.setup` and pass it to `mainBehavior`:

```scala
def apply(config: MyConfig): Behavior[Command] = {
  Behaviors.setup { ctx =>
    ctx.log.info("Actor initialized")
    mainBehavior(config, ctx)
  }
}

private def mainBehavior(
    config: MyConfig,
    ctx: ActorContext[Command]
): Behavior[Command] =
  Behaviors.receiveMessage {
    case SomeCommand =>
      ctx.log.debug("Processing SomeCommand")
      Behaviors.same
  }
}
```

Never import or use `org.slf4j.LoggerFactory` in actor code.

## No Await

**Never use `Await.result`, `Await.ready`, or `Await` in Pekko Typed code.** Blocking a thread defeats the purpose of the actor model and can starve the dispatcher. Use futures and callbacks, or `ask` patterns with `onComplete` / `map` / `flatMap` to chain asynchronous operations.

## Testing

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

## Example

See [ReadinessTracker](../backend/src/main/scala/com/archimond7450/archiemate/ReadinessTracker.scala) for a complete implementation following these conventions.
