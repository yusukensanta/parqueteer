// Side-by-side comparison: WITH vs WITHOUT implicit
// Run with: scala-cli implicit-comparison.scala

//> using scala "3.7.3"
//> using dep "io.circe::circe-core:0.14.10"

import io.circe._
import io.circe.syntax._

// ============================================
// Example 1: WITHOUT implicit (Manual)
// ============================================

object ManualEncoders {
  // No 'implicit' keyword
  val anyEncoder: Encoder[Any] = Encoder.instance {
    case null      => Json.Null
    case s: String => Json.fromString(s)
    case i: Int    => Json.fromInt(i)
    case d: Double => Json.fromDouble(d).getOrElse(Json.Null)
    case b: Boolean => Json.fromBoolean(b)
    case other     => Json.fromString(other.toString)
  }

  val mapEncoder: Encoder[Map[String, Any]] = Encoder.instance { map =>
    Json.obj(map.map { case (k, v) =>
      k -> anyEncoder.apply(v)  // Must call anyEncoder manually
    }.toSeq*)
  }

  val listMapEncoder: Encoder[List[Map[String, Any]]] = Encoder.instance { list =>
    Json.fromValues(list.map(mapEncoder.apply))  // Must call mapEncoder manually
  }
}

// ============================================
// Example 2: WITH implicit (Automatic)
// ============================================

object ImplicitEncoders {
  // With 'implicit' keyword
  implicit val anyEncoder: Encoder[Any] = Encoder.instance {
    case null      => Json.Null
    case s: String => Json.fromString(s)
    case i: Int    => Json.fromInt(i)
    case d: Double => Json.fromDouble(d).getOrElse(Json.Null)
    case b: Boolean => Json.fromBoolean(b)
    case other     => Json.fromString(other.toString)
  }

  implicit val mapEncoder: Encoder[Map[String, Any]] = Encoder.instance { map =>
    Json.obj(map.map { case (k, v) =>
      k -> anyEncoder.apply(v)  // Could also use implicitly[Encoder[Any]]
    }.toSeq*)
  }

  implicit val listMapEncoder: Encoder[List[Map[String, Any]]] = Encoder.instance { list =>
    Json.fromValues(list.map(mapEncoder.apply))
  }
}

// ============================================
// Usage Comparison
// ============================================

@main def compareImplicits(): Unit = {
  // Sample data (like Parquet row data)
  val rows: List[Map[String, Any]] = List(
    Map("name" -> "Alice", "age" -> 30, "salary" -> 75000.50),
    Map("name" -> "Bob", "age" -> 25, "salary" -> 65000.00),
    Map("name" -> "Charlie", "age" -> 35, "salary" -> 85000.75)
  )

  println("=" * 60)
  println("WITHOUT IMPLICIT - Manual Approach")
  println("=" * 60)

  // Must explicitly pass the encoder
  val json1 = ManualEncoders.listMapEncoder.apply(rows)
  //          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  //          Must reference the encoder manually
  println(json1.spaces2)
  println()

  // If you try without the encoder, it won't compile:
  // val json1 = rows.asJson  // ❌ COMPILE ERROR: could not find implicit

  println("=" * 60)
  println("WITH IMPLICIT - Automatic Approach")
  println("=" * 60)

  // Import implicit encoders
  import ImplicitEncoders._

  // Compiler automatically finds and uses listMapEncoder
  val json2 = rows.asJson
  //          ^^^^^^^^^^^
  //          Clean! Compiler inserts the encoder automatically
  println(json2.spaces2)
  println()

  // ============================================
  // Showing the difference in code volume
  // ============================================

  println("=" * 60)
  println("Code Comparison")
  println("=" * 60)

  println("Manual (WITHOUT implicit):")
  println("  val json = ManualEncoders.listMapEncoder.apply(rows)")
  println("  Characters: 54")
  println()

  println("Automatic (WITH implicit):")
  println("  import ImplicitEncoders._")
  println("  val json = rows.asJson")
  println("  Characters: 22 (excluding import)")
  println()

  // ============================================
  // Behavior is identical, just different syntax
  // ============================================

  println("=" * 60)
  println("Verification: Both produce same output?")
  println("=" * 60)
  println(s"json1 == json2: ${json1 == json2}")  // Should be true

  // ============================================
  // Demonstration: Nested implicit resolution
  // ============================================

  println()
  println("=" * 60)
  println("How Implicit Resolution Works")
  println("=" * 60)

  // When you call rows.asJson:
  println("1. Compiler sees: rows.asJson")
  println("2. rows type is: List[Map[String, Any]]")
  println("3. Compiler searches for: implicit Encoder[List[Map[String, Any]]]")
  println("4. Finds: ImplicitEncoders.listMapEncoder")
  println("5. Inserts it: rows.asJson(listMapEncoder)")
  println("6. Result: Clean code, same behavior!")

  // ============================================
  // Real-world scenario: Function composition
  // ============================================

  println()
  println("=" * 60)
  println("Function Composition Example")
  println("=" * 60)

  // WITHOUT implicit - must thread encoder through
  def processManual(data: List[Map[String, Any]]): String = {
    val json = ManualEncoders.listMapEncoder.apply(data)
    json.noSpaces
  }

  // WITH implicit - compiler handles it
  def processAutomatic(data: List[Map[String, Any]]): String = {
    import ImplicitEncoders._
    val json = data.asJson
    json.noSpaces
  }

  val result1 = processManual(rows)
  val result2 = processAutomatic(rows)

  println(s"Manual result length: ${result1.length} chars")
  println(s"Automatic result length: ${result2.length} chars")
  println(s"Results identical? ${result1 == result2}")

  // ============================================
  // Performance comparison (spoiler: identical)
  // ============================================

  println()
  println("=" * 60)
  println("Performance: WITH vs WITHOUT implicit")
  println("=" * 60)
  println("Runtime performance: IDENTICAL")
  println("Reason: Compiler inserts the same code in both cases")
  println("'implicit' is a compile-time feature, zero runtime overhead")

  // ============================================
  // Common mistake demonstration
  // ============================================

  println()
  println("=" * 60)
  println("Common Mistake: Forgetting to import")
  println("=" * 60)

  // This would fail to compile:
  // {
  //   val json = rows.asJson  // ❌ Error: could not find implicit
  // }

  // Solution: Import the implicit
  {
    import ImplicitEncoders._
    val json = rows.asJson  // ✅ Works!
    println("After import: Works perfectly")
  }

  println()
  println("=" * 60)
  println("Summary")
  println("=" * 60)
  println("WITHOUT implicit:")
  println("  ✓ Explicit and clear what's happening")
  println("  ✗ Verbose and repetitive")
  println("  ✗ Easy to pass wrong encoder")
  println()
  println("WITH implicit:")
  println("  ✓ Clean and concise")
  println("  ✓ Type-safe (compiler ensures correct encoder)")
  println("  ✓ Eliminates boilerplate")
  println("  ✗ Less obvious where values come from (need to understand implicits)")
}
