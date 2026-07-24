# General Best Practices

Scala conventions applicable across the entire ArchieMate codebase (backend and frontend).

---

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

## ScalaTest

Use `shouldEqual` instead of `shouldBe` to avoid Scala 3 infix rewrite warnings:

```scala
status shouldEqual StatusCodes.OK
```

Prefer `AnyWordSpecLike` over `AnyWordSpec` for all ScalaTest suites.

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

### Prefer case classes over string literals for JSON

When constructing or parsing JSON data, **always use case classes** that get implicitly encoded/decoded by circe. Using raw string literals is error-prone (typos, missing commas, wrong escaping) and bypasses compile-time type checking.

**Do this:**
```scala
case class UserResponse(id: String, name: String)

complete(StatusCodes.OK -> UserResponse("123", "alice"))
```

**Not this:**
```scala
complete(StatusCodes.OK -> """{"id": "123", "name": "alice"}""")
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

### Prefer short import paths over fully qualified names

When a type is imported, **always use the short name** in code. Do not use the fully qualified path unless you need to disambiguate between two types with the same name from different packages.

**Do this:**
```scala
import org.apache.pekko.actor.typed.scaladsl.Behaviors

Behaviors.supervise(actor()).onFailure[Throwable](SupervisorStrategy.resume)
```

**Not this:**
```scala
org.apache.pekko.actor.typed.scaladsl.Behaviors.supervise(actor())
```

**Not this either:**
```scala
Behaviors.supervise(actor()).onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.resume)
```

The same applies to `org.apache.pekko.actor.ClassicActorSystemProvider`, `org.apache.pekko.util.Timeout`, and any other imported type. Only reach for the fully qualified name when the compiler complains about ambiguity.

### Prefer Scala imports over Java imports

When a library provides both `scaladsl` and `javadsl` packages, **always prefer the `scaladsl` versions**. Never import from `javadsl` when a proper Scala equivalent exists.

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
