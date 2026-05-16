# Implicits in parqueteer

This document explains how Scala `implicit` definitions are used in the parqueteer project, why they are needed, and what changes in behavior occur when the `implicit` keyword (or the compiler flag enabling certain implicit features) is present vs absent.

## Quick Reference
- We use implicits for:
  - Type class instances (mainly Circe `Encoder` / `Decoder` derivations for configuration case classes)
  - (Scala 2 / 3 compatibility) automatic lifting / conversions explicitly enabled via `-language:implicitConversions`
- Removing the `implicit` keyword from these definitions breaks: automatic JSON (de)serialization resolution and generic service encoding.
- Disabling `-language:implicitConversions` breaks only code paths relying on legacy implicit conversion syntax (currently minimal / none besides library inheritance like parser combinators in generated docs).

## Where Implicits Appear
Key source file (as of writing):
```
src/main/scala/io/parqueteer/config/Configuration.scala
```
Excerpt:
```scala
implicit val s3ConfigDecoder: Decoder[S3Config] = deriveDecoder[S3Config]
implicit val s3ConfigEncoder: Encoder[S3Config] = deriveEncoder[S3Config]
...
implicit val appConfigDecoder: Decoder[AppConfig] = deriveDecoder[AppConfig]
implicit val appConfigEncoder: Encoder[AppConfig] = deriveEncoder[AppConfig]
```
These values are type class instances placed in (or imported into) implicit scope so that Circe can automatically locate them when you call, for example:
```scala
parser.decode[AppConfig](jsonString)
```
Without an implicit `Decoder[AppConfig]` in scope, the above call fails to compile with an error like "Cannot find implicit value for Decoder[AppConfig]".

## Why Implicits Are Needed Here
Circe (the JSON library) uses implicit resolution to locate encoders and decoders. By declaring them `implicit val`, we avoid having to thread explicit parameters everywhere, e.g.:
```scala
// With implicits
val cfg = parser.decode[AppConfig](json)

// Without implicits you would need (not standard Circe API, hypothetical style):
val cfg = parser.decode(json)(appConfigDecoder)
```
Scala's design for type classes before Scala 3 `given` / `using` syntax depends on `implicit` for ergonomic lookup.

## Scala 3 Migration Note
In Scala 3, `implicit val` type class instances can also be written using `given`:
```scala
given Decoder[AppConfig] = deriveDecoder[AppConfig]
```
The project (as configured) still uses the `implicit` form for cross-version clarity.

## Compiler Flag: -language:implicitConversions
In `build.sbt` we enable:
```scala
"-language:implicitConversions"
```
This flag authorizes definition and use of *implicit conversion* methods. An implicit conversion is typically:
```scala
implicit def fooToBar(x: Foo): Bar = ...
```
Currently parqueteer does not define custom implicit conversion methods in main source. Enabling the flag ensures that if libraries (e.g. parser combinators, older APIs) rely on them, the compiler will not emit warnings or errors.

### What If We Remove The Flag?
If we removed `-language:implicitConversions`:
- Existing implicit vals for encoders/decoders still compile (they are not conversions).
- Any (future) implicit `def` conversions would require either the flag, the `import scala.language.implicitConversions` statement, or refactoring.
- Potential warning suppression in dependent library code might change (compiler could emit warnings or fail if conversions are used without import).

## Behavioral Changes When Removing `implicit`
Consider this original line:
```scala
implicit val appConfigDecoder: Decoder[AppConfig] = deriveDecoder[AppConfig]
```
If changed to:
```scala
val appConfigDecoder: Decoder[AppConfig] = deriveDecoder[AppConfig]
```
Then any code invoking Circe derivation that *relies on automatic implicit search* will fail to compile unless it explicitly supplies `appConfigDecoder`. Runtime behavior does not change—this is a *compile-time* wiring mechanism. The ergonomic cost increases: every decode/encode call must be manually passed the instance.

## Implicit Scope & Import Patterns
Typical usage pattern:
```scala
import io.parqueteer.config.Configuration._
val cfg = parser.decode[AppConfig](json)
```
The wildcard import pulls all implicit vals into scope. Without the import, implicit resolution fails unless another object brings them into implicit scope.

## Risks / Pitfalls
- Ambiguity: Defining multiple implicit `Encoder[AppConfig]` values in the same scope will cause ambiguous implicit errors. Keep a single canonical definition.
- Shadowing: A local implicit with the same type can override the one from `Configuration`, possibly altering serialization.
- Upgrading to Scala 3: You may migrate `implicit val` to `given` to embrace new style; mixing styles is allowed but can confuse readers.

## Recommendations
- Keep implicit encoders/decoders in a dedicated object to manage import clarity (current setup is fine).
- Avoid defining implicit conversions (`implicit def`) unless absolutely necessary; prefer extension methods in Scala 3 (`extension (x: Foo)` syntax) for clarity.
- When adding new configuration case classes, follow the existing pattern:
```scala
final case class NewSection(foo: Int, bar: String)
object Configuration {
  implicit val newSectionDecoder: Decoder[NewSection] = deriveDecoder
  implicit val newSectionEncoder: Encoder[NewSection] = deriveEncoder
}
```

## Migration Path to `given` (Optional Future)
Example rewrite:
```scala
object Configuration:
  import io.circe.generic.semiauto._
  given Decoder[S3Config] = deriveDecoder
  given Encoder[S3Config] = deriveEncoder
  // ... and so on
```
Usage then becomes:
```scala
import io.parqueteer.config.Configuration.given
parser.decode[AppConfig](json) // works via given instances
```
Not urgent, but modernizes style.

## Summary
- `implicit` here supplies type class instances (Encoders/Decoders) to library methods without boilerplate.
- Removing `implicit` degrades compile-time inference; explicit passing becomes necessary.
- The `-language:implicitConversions` flag is precautionary / compatibility-oriented; its removal currently has limited effect.
- Future Scala 3 idioms (`given`, extension methods) can progressively replace legacy implicit patterns for clarity.

