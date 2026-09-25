package de.dnpm.cdn.etl.util

import play.api.libs.json.{
  Json,
  Reads,
  Writes,
  OWrites
}


final case class Collection[T](entries: List[T])
{
  def size = entries.size
}

object Collection
{

  implicit def reads[T: Reads]: Reads[Collection[T]] =
    Json.reads[Collection[T]]

  implicit def writes[T: Writes]: OWrites[Collection[T]] =
    OWrites {
      coll =>
        Json.obj(
          "size"    -> Json.toJson(coll.size),
          "entries" -> Json.toJson(coll.entries),
        )
    }
}
