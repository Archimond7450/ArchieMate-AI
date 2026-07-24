# Frontend Best Practices

Guidelines for writing clean, idiomatic Scala.js / Laminar frontend code in the ArchieMate codebase.

For general Scala conventions (Scala 3 syntax, given/using, extension methods, imports, circe), see [general-best-practices.md](general-best-practices.md).

---

## Laminar Element Construction

### Use `children <--` for dynamic sequences

When rendering a dynamic list of children, use `children <--` with a `Var[Seq[Element]]`:

```scala
div(
  children <-- myVar.signal.map { items =>
    items.map(item => span(item))
  }
)
```

### Use `child <--` for a single dynamic element

When the dynamic content is **exactly one element**, use `child <--` instead of `children <--` with a single-element `Seq`. This is more efficient and avoids the unnecessary `Seq` allocation:

```scala
// Good — single dynamic child
div(
  child <-- UserStore.displayNameVar.signal.map { name =>
    if (name.nonEmpty) name else "Unknown"
  }
)

// Avoid — single-element Seq with children
div(
  children <-- UserStore.displayNameVar.signal.map { name =>
    Seq(if (name.nonEmpty) name else "Unknown")
  }
)
```

### Use `text` for reactive text content

When rendering reactive text inside an element, use `text <--`:

```scala
span(
  text <-- UserStore.isTwitchConnected.signal.map { connected =>
    if (connected) "Connected" else "Disconnected"
  }
)
```

### Use `children <--` for dynamic child sequences

When the number of children changes, use `children <--`:

```scala
div(
  children <-- UserStore.isTwitchConnected.signal.map { connected =>
    if (connected) Seq(span("Connected")) else Seq(span("Disconnected"))
  }
)
```

### Use `className` for dynamic class names

When class names are reactive, use `className <--`:

```scala
div(
  className <-- UserStore.isTwitchConnected.signal.map { connected =>
    if (connected) "text-green-600" else "text-red-600"
  }
)
```

### Use `cls` for static class names

When class names are static, use `cls`:

```scala
div(
  cls("bg-white", "rounded-lg", "shadow-sm")
)
```

---

## SVG in Laminar

Use `svg.svg(...)` with `svg.xxx(...)` for attributes:

```scala
svg.svg(
  svg.className("w-5 h-5 text-blue-500"),
  svg.xmlns("http://www.w3.org/2000/svg"),
  svg.fill("none"),
  svg.viewBox("0 0 24 24"),
  svg.stroke("currentColor"),
  svg.strokeWidth("2"),
  svg.path(
    svg.strokeLineCap("round"),
    svg.strokeLineJoin("round"),
    svg.d("M13 16h-1v-4h-1...")
  )
)
```

Note: `strokeLineCap` and `strokeLineJoin` use Pascal case (camelCase), not snake case.

---

## Reactivity Patterns

### Use `Var` for mutable state

```scala
val menuOpen = Var(false)
menuOpen.set(true)
menuOpen.update(!_)
```

### Use `signal` to create reactive subscribers

```scala
val myVar = Var("default")
val mySignal = myVar.signal
```

### Use `onClick -->` for event handlers

```scala
button(
  onClick --> { (_: dom.Event) =>
    menuOpen.update(!_)
  },
  text("Toggle")
)
```

---

## DOM Access

Import `org.scalajs.dom` explicitly. Use `dom.window.location.href` for navigation, `dom.document.querySelector` for DOM queries, and `dom.fetch` for HTTP requests.

```scala
import org.scalajs.dom

dom.window.location.href = "/dashboard"
val el = dom.document.querySelector("#app")
```

---

## Fetch API

When using `fetch`, set `credentials = "include"` to send cookies:

```scala
val headers = new dom.Headers()
headers.set("Accept", "application/json")
val init = js.Dynamic.literal(
  method = "GET",
  credentials = "include",
  headers = headers
).asInstanceOf[dom.RequestInit]

fetch(s"$ApiBaseUrl/me", init).`then` { (resp: dom.Response) =>
  if (resp.ok) {
    resp.json().`then` { (raw: js.Any) =>
      // handle success
    }
  }
}
```

---

## Component Structure

### Keep components in `components/` or `pages/`

- **Components** — reusable UI pieces (Header, Footer, UserMenu) go in `components/`
- **Pages** — full-page views (DashboardPage, HomePage) go in `pages/`

### Use `object` for stateless components

Components that don't need internal mutable state should be `object`s with a `render()` method.

### Use `Var` for component-local state

Components that need local state should declare `Var`s at the top level of the object and use them in `render()`.

---

## Tailwind CSS

### Use Tailwind utility classes

Apply styling exclusively through Tailwind utility classes. Avoid custom CSS.

### Support dark mode

Use `dark:` prefixes for dark mode variants:

```scala
cls("bg-white dark:bg-gray-800", "text-gray-900 dark:text-white")
```

### Responsive design

Use Tailwind's responsive prefixes (`sm:`, `md:`, `lg:`, `xl:`) for responsive layouts.

---

## Testing

### Frontend Scala tests

Use `AnyWordSpecLike` for frontend Scala tests. DOM-dependent rendering is tested via E2E tests (Playwright).

### E2E tests with Playwright

- Place E2E test files in `frontend-test/e2e/`
- Test against the Docker container running on `http://localhost:8080`
- Use `baseURL` configured in `playwright.config.ts`
- Test across all browsers: Chromium, Firefox, Safari

---

## Imports

### Laminar and givens

```scala
import com.raquo.laminar.api.L.{*, given}
```

### Scala.js DOM

```scala
import org.scalajs.dom
```

### Short names over fully qualified

When a type is imported, **always use the short name** in code. Do not use the fully qualified path unless you need to disambiguate.

---

## Error Handling

### Use `js.UndefOr` for optional JavaScript values

```scala
val value = obj.field.asInstanceOf[js.UndefOr[Double]]
if (!value.isEmpty) {
  val num = value.asInstanceOf[Double]
}
```

### Use `js.Date` for JavaScript dates

```scala
val date = new js.Date(timestamp.toDouble)
```

Never use `java.util.Date` for values from JavaScript APIs.

---

## Button Patterns

### Simple buttons

```scala
button(
  cls("bg-indigo-600 text-white px-4 py-2 rounded-md"),
  onClick --> { (_: dom.Event) =>
    handleClick()
  },
  text("Click me")
)
```

### Dynamic content in buttons

For dynamic text inside buttons, use `children <--` with a `Var`:

```scala
val label = Var("Loading")
button(
  children <-- label.signal.map { txt =>
    Seq(span(txt))
  }
)
```
