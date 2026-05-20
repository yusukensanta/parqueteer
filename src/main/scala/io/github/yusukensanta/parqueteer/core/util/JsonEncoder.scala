package io.github.yusukensanta.parqueteer.core.util

import io.circe.Json

object JsonEncoder {
  def encodeAny(value: Any): Json = value match {
    case null      => Json.Null
    case s: String => Json.fromString(s)
    case i: Int    => Json.fromInt(i)
    case l: Long   => Json.fromLong(l)
    case d: Double =>
      if (d.isNaN || d.isInfinity) Json.Null
      else Json.fromDoubleOrNull(d)
    case f: Float =>
      if (f.isNaN || f.isInfinity) Json.Null
      else Json.fromFloatOrNull(f)
    case b: Boolean             => Json.fromBoolean(b)
    case bd: BigDecimal         => Json.fromBigDecimal(bd)
    case bi: BigInt             => Json.fromBigInt(bi)
    case d: java.time.LocalDate => Json.fromString(d.toString)
    case i: java.time.Instant   => Json.fromString(i.toString)
    case bytes: Array[Byte] =>
      Json.fromString(java.util.Base64.getEncoder.encodeToString(bytes))
    case list: List[_] => Json.arr(list.map(encodeAny)*)
    case arr: Array[_] => Json.arr(arr.map(encodeAny).toSeq*)
    case map: Map[_, _] =>
      Json.obj(map.map { case (k, v) => k.toString -> encodeAny(v) }.toSeq*)
    case Some(v) => encodeAny(v)
    case None    => Json.Null
    case other   => Json.fromString(other.toString)
  }
}
