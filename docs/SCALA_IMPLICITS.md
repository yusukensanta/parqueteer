# Understanding Scala Implicits

A comprehensive guide to `implicit` keyword in Scala, with real examples from parqueteer.

---

## What is `implicit`?

`implicit` is a Scala feature that allows the compiler to **automatically pass parameters** to functions without you writing them explicitly.

Think of it as:
- **Automatic dependency injection**
- **Invisible plumbing** that makes code cleaner
- **Type-driven parameter passing**

---

## Illustrative Example

> **Note**: The examples below use `ParquetServiceEncoders` as an illustrative name — this object does not exist verbatim in the codebase. The codebase uses Scala 3 `given` syntax rather than `implicit val`. The concepts demonstrated here apply directly.

### WITHOUT implicit/given (Verbose)

```scala
// Without implicit - you must pass the encoder manually every time
object MyEncoders {
  val anyEncoder: Encoder[Any] = Encoder.instance {
    case s: String => Json.fromString(s)
    case i: Int    => Json.fromInt(i)
    // ... more cases
  }
}

// Using it - VERBOSE!
import MyEncoders._

val data = Map("name" -> "Alice", "age" -> 30)
val json = data.asJson(anyEncoder)  // ❌ Must pass encoder explicitly
```

### WITH given (Clean — Scala 3 style used in this project)

```scala
// With given - compiler passes it automatically
object MyEncoders:
  given Encoder[Map[String, Any]] = Encoder.instance { map =>
    Json.obj(map.map { case (k, v) => k -> Json.fromString(v.toString) }.toSeq*)
  }

// Using it - CLEAN!
import MyEncoders.given

val data = Map("name" -> "Alice", "age" -> 30)
val json = data.asJson  // ✅ Compiler finds the given encoder automatically!
```

**The compiler automatically inserts**: `data.asJson(given_Encoder_Map_String_Any)`

---

## How Implicit Resolution Works

### 1. Implicit Parameters

```scala
// Function that needs an encoder
def toJson[A](value: A)(implicit encoder: Encoder[A]): Json = {
  encoder.apply(value)
}

// Define implicit encoder
implicit val stringEncoder: Encoder[String] = Encoder.instance(Json.fromString)

// Usage
toJson("hello")  // ✅ Compiler finds stringEncoder automatically
// Equivalent to: toJson("hello")(stringEncoder)
```

### 2. Compiler Search Process

When you call `toJson("hello")`, the compiler:

1. **Sees**: Function needs `Encoder[String]`
2. **Searches**: Current scope for `implicit val x: Encoder[String]`
3. **Finds**: `stringEncoder` matches the type
4. **Inserts**: `toJson("hello")(stringEncoder)` automatically

---

## Actual parqueteer Example

### From `cli/CliOutputFormatter.scala` (illustrative — Scala 3 `given` style)

The codebase encodes Parquet rows to JSON using a `given` chain (Scala 3 style):

```scala
// Given instances — compiler resolves these automatically
given Encoder[CellValue] = Encoder.instance {
  case CellValue.Null    => Json.Null
  case CellValue.Str(s)  => Json.fromString(s)
  case CellValue.I32(i)  => Json.fromInt(i)
  case CellValue.I64(l)  => Json.fromLong(l)
  // ... more cases
}

given Encoder[Map[String, CellValue]] = Encoder.instance { row =>
  Json.obj(row.map { case (k, v) => k -> v.asJson }.toSeq*)
}
```

**Key point**: the `Map` encoder depends on the `CellValue` encoder — the compiler resolves the `CellValue` given automatically when encoding each value.

---

## Behavior Difference: With vs Without

### Example: Converting Parquet data to JSON

```scala
// Parquet row data
val row: Map[String, Any] = Map(
  "name" -> "Alice",
  "age" -> 30,
  "salary" -> 75000.50
)
```

### WITHOUT implicit

```scala
object Encoders {
  // No 'implicit' keyword
  val anyEncoder: Encoder[Any] = Encoder.instance { ... }
  val mapEncoder: Encoder[Map[String, Any]] = Encoder.instance { ... }
}

// Usage - MUST pass encoder manually
import Encoders._
import io.circe.syntax._

val json = row.asJson(mapEncoder)  // ❌ Verbose
//                   ^^^^^^^^^^^
//                   Required every single time
```

**Problems**:
- ❌ Repetitive - must pass encoder everywhere
- ❌ Easy to forget which encoder to use
- ❌ Clutters code with plumbing

### WITH implicit

```scala
object Encoders {
  // With 'implicit' keyword
  implicit val anyEncoder: Encoder[Any] = Encoder.instance { ... }
  implicit val mapEncoder: Encoder[Map[String, Any]] = Encoder.instance { ... }
}

// Usage - compiler handles it
import Encoders._
import io.circe.syntax._

val json = row.asJson  // ✅ Clean!
//                        Compiler automatically uses mapEncoder
```

**Benefits**:
- ✅ Clean, readable code
- ✅ Type-safe (compiler ensures correct encoder)
- ✅ Automatic dependency resolution

---

## Real Behavior Change Example

Let's trace through actual parqueteer code:

### Scenario: Converting Parquet rows to JSON

```scala
// In formatAsJSON method (simplified)
private def formatAsJSON(content: FileContent): String = {
  import io.circe.syntax._
  content.rows.asJson.spaces2  // How does this work?
}
```

### Step-by-Step Without Implicit

```scala
// Step 1: Define encoder manually
val rowEncoder: Encoder[Map[String, Any]] = Encoder.instance { row =>
  Json.obj(row.map { case (k, v) =>
    // Need to manually encode each value type
    val encodedValue = v match {
      case s: String  => Json.fromString(s)
      case i: Int     => Json.fromInt(i)
      case d: Double  => Json.fromDouble(d).getOrElse(Json.Null)
      case _          => Json.Null
    }
    k -> encodedValue
  }.toSeq*)
}

// Step 2: Define list encoder manually
val listEncoder: Encoder[List[Map[String, Any]]] = Encoder.instance { list =>
  Json.fromValues(list.map(rowEncoder.apply))  // Must reference rowEncoder
}

// Step 3: Use it manually
val json = listEncoder.apply(content.rows)  // ❌ Verbose
```

### Step-by-Step With given (Scala 3 style)

```scala
// Step 1: Define given encoders (done once in a companion object or module)
object MyEncoders:
  given Encoder[CellValue] = Encoder.instance { ... }
  given Encoder[Map[String, CellValue]] = Encoder.instance { ... }
  given Encoder[List[Map[String, CellValue]]] = Encoder.instance { ... }

// Step 2: Import givens
import MyEncoders.given
import io.circe.syntax._

// Step 3: Use it cleanly
val json = content.rows.asJson  // ✅ Compiler handles everything!
```

**What the compiler does**:
1. Sees `content.rows.asJson`
2. `rows` is `List[Map[String, CellValue]]`
3. Searches for a `given Encoder[List[Map[String, CellValue]]]`
4. Finds it in `MyEncoders`
5. Rewrites to: `content.rows.asJson(given_Encoder_List)`

---

## Common Implicit Patterns

### 1. Type Class Pattern (Most Common)

```scala
// Define type class
trait Encoder[A] {
  def encode(value: A): Json
}

// Provide implicit instances
object Encoders {
  implicit val stringEncoder: Encoder[String] = new Encoder[String] {
    def encode(value: String): Json = Json.fromString(value)
  }

  implicit val intEncoder: Encoder[Int] = new Encoder[Int] {
    def encode(value: Int): Json = Json.fromInt(value)
  }
}

// Generic function using type class
def toJson[A](value: A)(implicit encoder: Encoder[A]): Json = {
  encoder.encode(value)
}

// Usage
import Encoders._
toJson("hello")  // Uses stringEncoder
toJson(42)       // Uses intEncoder
```

### 2. Extension Methods (Syntax Pattern)

```scala
// Add methods to existing types
implicit class RichString(s: String) {
  def isPalindrome: Boolean = s == s.reverse
  def times(n: Int): String = s * n
}

// Usage
"racecar".isPalindrome  // true
"ha".times(3)           // "hahaha"
```

### 3. Context Bounds (Shorthand)

```scala
// Long form
def process[A](value: A)(implicit encoder: Encoder[A]): Json = {
  encoder.encode(value)
}

// Short form (context bound)
def process[A: Encoder](value: A): Json = {
  implicitly[Encoder[A]].encode(value)
}

// Both are equivalent!
```

---

## Debugging Implicits

### See What the Compiler Finds

```bash
# Compile with implicit info
scalac -Xlog-implicits YourFile.scala

# Or in sbt
sbt -Dscala.implicits=true compile
```

### Common Errors

#### 1. Ambiguous Implicits

```scala
implicit val encoder1: Encoder[String] = ...
implicit val encoder2: Encoder[String] = ...

toJson("hello")  // ❌ Error: ambiguous implicit values
```

**Solution**: Only one implicit per type in scope.

#### 2. Not Found

```scala
toJson("hello")  // ❌ Error: could not find implicit value for Encoder[String]
```

**Solution**: Import or define the implicit.

```scala
import Encoders._  // ✅ Now it works
toJson("hello")
```

#### 3. Wrong Import

```scala
// Defined here
object MyEncoders {
  implicit val stringEncoder: Encoder[String] = ...
}

// Used here
toJson("hello")  // ❌ Error: not found
```

**Solution**: Import it!

```scala
import MyEncoders._  // ✅ Now visible
toJson("hello")
```

---

## Best Practices

### ✅ DO

1. **Use for type classes** (Encoder, Decoder, Ordering)
   ```scala
   implicit val userEncoder: Encoder[User] = deriveEncoder[User]
   ```

2. **One implicit per type** in scope
   ```scala
   object Encoders {
     implicit val stringEncoder: Encoder[String] = ...
     implicit val intEncoder: Encoder[Int] = ...
   }
   ```

3. **Name implicits descriptively**
   ```scala
   implicit val userJsonEncoder: Encoder[User] = ...  // Good
   implicit val x: Encoder[User] = ...                 // Bad
   ```

4. **Put in companion objects** for auto-import
   ```scala
   case class User(name: String, age: Int)
   object User {
     implicit val encoder: Encoder[User] = deriveEncoder[User]
   }
   // Automatically available when User is in scope!
   ```

### ❌ DON'T

1. **Don't use for simple parameters**
   ```scala
   def greet(name: String)(implicit greeting: String) = s"$greeting, $name"
   // ❌ Just use a regular parameter!
   ```

2. **Don't create multiple implicits for same type**
   ```scala
   implicit val encoder1: Encoder[String] = ...
   implicit val encoder2: Encoder[String] = ...  // ❌ Ambiguous!
   ```

3. **Don't make everything implicit**
   ```scala
   implicit val config: Config = ...
   implicit val logger: Logger = ...
   implicit val database: Database = ...
   // ❌ Too much magic!
   ```

---

## Summary: With vs Without

| Aspect | Without `implicit` | With `implicit` |
|--------|-------------------|-----------------|
| **Syntax** | Must pass manually | Auto-inserted |
| **Code** | Verbose | Clean |
| **Safety** | Runtime errors | Compile-time checks |
| **Usage** | `toJson(data)(encoder)` | `toJson(data)` |
| **Maintenance** | Change every call | Change definition once |

---

## Key Takeaway

`★ Insight ─────────────────────────────────────`

**Implicits = Compiler-Assisted Dependency Injection**

Instead of manually threading parameters through code:
```scala
encode(data, encoder1, encoder2, encoder3)  // ❌ Manual
```

The compiler does it for you:
```scala
encode(data)  // ✅ Automatic
// Compiler inserts: encode(data)(encoder1)(encoder2)(encoder3)
```

**Use when**: You have "context" that many functions need (encoders, decoders, configurations)

**Avoid when**: Parameters are explicit business logic (user input, function-specific data)

`─────────────────────────────────────────────────`

---

*Last updated: October 2025*
