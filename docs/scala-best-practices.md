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
