# Scala Best Practices

Idiomatic Scala 3 conventions used across the ArchieMate codebase.

## Scala 3 Syntax

Use **Scala 3 syntax** everywhere. The only exception is the Python-like indented syntax — we prefer traditional `{` and `}` blocks for readability and diff clarity.

## Given / Using for Implicits

### Given that may be used in normal calls

```scala
given askTimeout: Timeout = 5.seconds
```

### Given that will only be passed through as an implicit parameter

```scala
given ActorContext[Command] = ctx
```

### Receiving parameters that may be used by name

```scala
def fn(using ec: ExecutionContext, askTimeout: Timeout): Unit = {
  // ec and askTimeout are available as named values
}
```

### Receiving parameters that will only be forwarded

```scala
def fn(using ExecutionContext, Timeout): Unit = {
  // Parameters are available only to other `using` clauses
}
```

### Multiple `using` clauses for different purposes

```scala
def fn()(using ctx: ActorContext[SomeActor.Command])(using ExecutionContext, Timeout): Unit = {
  // ctx is named; the other two are only forwarded
}
```

## Extension Methods

Use the `extension` keyword:

```scala
extension (str: String)
  def differentCharCount: Int = str.toSet.size
```

## Implicit Conversions

Use `given Conversion`:

```scala
given Conversion[String, Option[Int]] = _.toIntOption
```

## Logging

Use `classOf[ObjectName.type]` for singleton objects, not `classOf[ObjectName]`:

```scala
private val logger = LoggerFactory.getLogger(classOf[MyApp.type])
```

## ScalaTest

Use `shouldEqual` instead of `shouldBe` to avoid Scala 3 infix rewrite warnings:

```scala
status shouldEqual StatusCodes.OK
```

## Imports

### Everything from a package (values only)

```scala
import com.archimond7450.archiemate.settings.*
```

### Everything from a package plus all givens/implicits

```scala
import com.raquo.laminar.api.L.{*, given}
```

## JSON (circe)

Use **derived** decoders and encoders. Define a `given Decoder[A]` and `given Encoder[A]` in the companion object of your case class.

### Common configuration (backend ↔ frontend)

One shared circe configuration for all internal API communication:

```scala
given Decoder[Foo] = deriveDecoder
given Encoder[Foo]   = deriveEncoder
```

### Per-channel configuration

Each supported channel (Twitch, Kick, YouTube) gets its own circe configuration, since external wire formats may differ:

```scala
// shared/twitch/
given Decoder[TwitchEvent] = deriveDecoder
given Encoder[TwitchEvent]   = deriveEncoder

// shared/kick/
given Decoder[KickEvent] = deriveDecoder
given Encoder[KickEvent]   = deriveEncoder
```

This keeps the common internal format clean while allowing per-channel adaptations.

### Prefer case classes over string literals for HTTP responses

When returning JSON data from HTTP endpoints, **always use case classes** that get implicitly encoded by circe. Using raw string literals is error-prone (typos, missing commas, wrong escaping) and bypasses compile-time type checking.

**Do this:**
```scala
case class UserResponse(id: String, name: String)

complete(StatusCodes.OK -> UserResponse("123", "alice"))
```

**Not this:**
```scala
complete(StatusCodes.OK -> """{"id": "123", "name": "alice"}""")
```

### Prefer Scala imports over Java imports

When Pekko (or any library) provides both `scaladsl` and `javadsl` packages, **always prefer the `scaladsl` versions**. Never import from `javadsl` when a proper Scala equivalent exists.

**Do this:**
```scala
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
```

**Not this:**
```scala
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.server.Directives._
```

### ClassicActorSystemProvider from typed ActorSystem

When `ClassicActorSystemProvider` is required, retrieve it from the typed Actor System's `classicSystem` property. **Do not create a new `ClassicActorSystemProvider` manually** (e.g., via `new ClassicActorSystemProvider { def classicSystem = ... }`) unless you are in a test or the root `main` method where no typed system exists yet.

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

### Prefer helper actors over nested asks in HTTP endpoints

When an HTTP endpoint needs to perform multiple actor asks (e.g., get user info then fetch related data), **prefer asking a helper actor once** instead of performing multiple nested asks. This reduces latency (parallel execution), simplifies error handling, and avoids callback nesting.

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
