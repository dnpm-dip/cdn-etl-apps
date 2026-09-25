package de.dnpm.cdn.etl.submissions

import java.io.File
import java.nio.file.{
  Files,
  Path
}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{
  ExecutionContext,
  Future
}
import cats.data.EitherNel
import cats.syntax.either._
import de.dnpm.bfarm.model.onco.OncologySubmission
import de.dnpm.bfarm.model.rd.RDSubmission
import play.api.libs.json.Json


trait Exporter[F[+_],Ctx,+Outcome]
{

  def process(
    submission: OncologySubmission
  )(
    implicit ctx: Ctx
  ): F[EitherNel[String,Outcome]]

  def process(
    submission: RDSubmission
  )(
    implicit ctx: Ctx
  ): F[EitherNel[String,Outcome]]

}

class FileExporter(dir: File) extends Exporter[Future,ExecutionContext,Path]
{

  dir.mkdirs

  private def file(submission: OncologySubmission): File =
    new File(dir,s"OncologySubmission_${submission.metaData.submission.submitterId}_${submission.metaData.tanC}.json")
      

  private def file(submission: RDSubmission): File =
    new File(dir,s"RDSubmission_${submission.metaData.submission.submitterId}_${submission.metaData.tanC}.json")


  def process(
    submission: OncologySubmission
  )(
    implicit ctx: ExecutionContext
  ): Future[EitherNel[String,Path]] =
    Future {
      Files.write(
        file(submission).toPath,
        Json.stringify(Json.toJson(submission)).getBytes(UTF_8)
      )
    }
    .map(_.asRight)
    .recover { 
      case t => t.getMessage.asLeft
    }
    .map(_.toEitherNel)


  def process(
    submission: RDSubmission
  )(
    implicit ctx: ExecutionContext
  ): Future[EitherNel[String,Path]] =
    Future {
      Files.write(
        file(submission).toPath,
        Json.stringify(Json.toJson(submission)).getBytes(UTF_8)
      )
    }
    .map(_.asRight)
    .recover { 
      case t => t.getMessage.asLeft
    }
    .map(_.toEitherNel)

}

