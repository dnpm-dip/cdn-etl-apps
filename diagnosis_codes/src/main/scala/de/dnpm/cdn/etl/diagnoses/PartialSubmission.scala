package de.dnpm.cdn.etl.diagnoses

import java.time.LocalDateTime
import cats.data.NonEmptyList
import de.dnpm.dip.model.{
  Diagnosis,
}
import play.api.libs.json.{
  Json,
  OFormat,
  Format
}


case class PartialSubmission[D <: Diagnosis]
(
  submittedAt: LocalDateTime,
  diagnoses: NonEmptyList[D]
)

object PartialSubmission
{

  import de.dnpm.dip.util.json.{readsNel,writesNel}

  implicit def format[D <: Diagnosis: Format]: OFormat[PartialSubmission[D]] =
    Json.format[PartialSubmission[D]]
}
